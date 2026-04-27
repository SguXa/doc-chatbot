import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as sonner from 'sonner'
import { DocumentUpload } from './DocumentUpload'
import { useAuthStore } from '@/stores/authStore'

interface FakeXhrInstance {
  open: (method: string, url: string) => void
  setRequestHeader: (name: string, value: string) => void
  send: (body: unknown) => void
  upload: {
    onprogress: ((event: ProgressEvent) => void) | null
    onload: (() => void) | null
  }
  onload: (() => void) | null
  onerror: (() => void) | null
  status: number
  statusText: string
  response: string
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

let createdXhrs: FakeXhrInstance[] = []

class FakeXMLHttpRequest implements FakeXhrInstance {
  upload: { onprogress: ((event: ProgressEvent) => void) | null; onload: (() => void) | null } = {
    onprogress: null,
    onload: null,
  }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  status = 0
  statusText = ''
  response = ''
  method = ''
  url = ''
  headers: Record<string, string> = {}
  body: unknown = null

  constructor() {
    createdXhrs.push(this)
  }

  open(method: string, url: string) {
    this.method = method
    this.url = url
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }

  send(body: unknown) {
    this.body = body
  }
}

function lastXhr(): FakeXhrInstance {
  if (createdXhrs.length === 0) throw new Error('No XHR was created')
  return createdXhrs[createdXhrs.length - 1]
}

function renderUpload(props: { isReindexing?: boolean } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <DocumentUpload {...props} />
      </QueryClientProvider>,
    ),
  }
}

function dropFile(target: HTMLElement, file: File) {
  fireEvent.drop(target, { dataTransfer: { files: [file] } })
}

function makeFile(name: string, type = 'application/octet-stream'): File {
  return new File(['payload'], name, { type })
}

function dropZone(): HTMLElement {
  return screen.getByRole('button', { name: /upload document/i })
}

function respondWith(status: number, body: unknown) {
  const xhr = lastXhr()
  act(() => {
    if (xhr.upload.onprogress) {
      xhr.upload.onprogress({ lengthComputable: true, loaded: 100, total: 100 } as ProgressEvent)
    }
    if (xhr.upload.onload) xhr.upload.onload()
  })
  xhr.status = status
  xhr.statusText = ''
  xhr.response = body !== undefined ? JSON.stringify(body) : ''
  act(() => {
    if (xhr.onload) xhr.onload()
  })
}

describe('DocumentUpload', () => {
  beforeEach(() => {
    createdXhrs = []
    vi.stubGlobal('XMLHttpRequest', FakeXMLHttpRequest as unknown as typeof XMLHttpRequest)
    vi.spyOn(sonner.toast, 'success').mockImplementation(() => '' as never)
    vi.spyOn(sonner.toast, 'error').mockImplementation(() => '' as never)
    useAuthStore.setState({ token: 'test-token', isAuthenticated: true })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    useAuthStore.setState({ token: null, isAuthenticated: false })
    localStorage.clear()
  })

  it('rejects unsupported extensions client-side without firing XHR', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('virus.exe'))

    expect(createdXhrs).toHaveLength(0)
    expect(screen.getByRole('alert')).toHaveTextContent(
      /only \.docx and \.pdf are supported/i,
    )
    expect(sonner.toast.error).toHaveBeenCalledWith(
      'Only .docx and .pdf are supported',
    )
  })

  it('opens XHR with correct URL, method, FormData payload and Authorization header on .docx drop', () => {
    renderUpload()
    const file = makeFile('sample.docx')
    dropFile(dropZone(), file)

    const xhr = lastXhr()
    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe('/api/admin/documents')
    expect(xhr.headers['Authorization']).toBe('Bearer test-token')
    expect(xhr.body).toBeInstanceOf(FormData)
    const sent = xhr.body as FormData
    expect(sent.get('file')).toBe(file)
  })

  it('shows progress bar during upload then switches to "Parsing document…" after upload.onload', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    const xhr = lastXhr()

    act(() => {
      xhr.upload.onprogress?.({
        lengthComputable: true,
        loaded: 50,
        total: 100,
      } as ProgressEvent)
    })

    const bar = screen.getByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '50')
    expect(screen.getByText(/uploading… 50%/i)).toBeInTheDocument()

    act(() => {
      xhr.upload.onprogress?.({
        lengthComputable: true,
        loaded: 100,
        total: 100,
      } as ProgressEvent)
    })
    expect(screen.getByText(/uploading… 100%/i)).toBeInTheDocument()

    act(() => {
      xhr.upload.onload?.()
    })
    expect(screen.getByText(/parsing document…/i)).toBeInTheDocument()
  })

  it('on 201 response: shows success toast, invalidates documents query, returns to idle', async () => {
    const { queryClient } = renderUpload()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    dropFile(dropZone(), makeFile('a.docx'))

    respondWith(201, {
      id: 1,
      filename: 'a.docx',
      fileType: 'docx',
      fileSize: 7,
      fileHash: 'h',
      chunkCount: 1,
      imageCount: 0,
      indexedAt: '2026-04-27 09:00:00',
      createdAt: '2026-04-27 09:00:00',
    })

    await waitFor(() => {
      expect(sonner.toast.success).toHaveBeenCalledWith('Uploaded')
    })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['documents'] })
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.queryByText(/parsing document/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('on 400 invalid_upload unsupported_extension: shows mapped message', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(400, {
      error: 'invalid_upload',
      reason: 'unsupported_extension',
      message: 'Only .docx and .pdf are supported',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      /only \.docx and \.pdf are supported/i,
    )
  })

  it('on 400 invalid_upload malformed_multipart: shows "File is corrupted"', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(400, {
      error: 'invalid_upload',
      reason: 'malformed_multipart',
      message: 'Bad multipart',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      /file is corrupted, please retry/i,
    )
  })

  it('on 400 unreadable_document: shows "Could not read file: <message>"', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(400, {
      error: 'unreadable_document',
      reason: 'corrupted',
      message: 'XML structure is invalid',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      /could not read file: xml structure is invalid/i,
    )
  })

  it('on 400 empty_content: shows "No extractable text in file"', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(400, {
      error: 'empty_content',
      reason: 'no_extractable_content',
      message: 'No text content found',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      /no extractable text in file/i,
    )
  })

  it('on 409 duplicate_document: opens dialog with existing filename and indexedAt', async () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(409, {
      error: 'duplicate_document',
      message: 'A document with identical content has already been indexed.',
      existing: {
        id: 7,
        filename: 'original.docx',
        fileType: 'docx',
        fileSize: 1024,
        fileHash: 'abc',
        chunkCount: 4,
        imageCount: 0,
        indexedAt: '2026-04-27 08:30:00',
        createdAt: '2026-04-27 08:30:00',
      },
    })

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('original.docx')
    expect(dialog).toHaveTextContent('2026-04-27 08:30:00')
  })

  it('on 413 file_too_large: shows "File exceeds 100 MB limit"', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(413, {
      error: 'invalid_upload',
      reason: 'file_too_large',
      message: 'too big',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(/file exceeds 100 mb limit/i)
  })

  it('on 415 invalid_content_type: shows defensive "Invalid request format"', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(415, {
      error: 'invalid_upload',
      reason: 'invalid_content_type',
      message: 'expected multipart',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(/invalid request format/i)
  })

  it('on 503 ollama_unavailable: shows the rolled-back retry message', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(503, {
      error: 'ollama_unavailable',
      message: 'Embedding service unavailable',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      /ollama is unavailable\. upload was rolled back, retry shortly\./i,
    )
  })

  it('on 503 reindex_in_progress: shows retry-in-a-minute message', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(503, {
      error: 'reindex_in_progress',
      message: 'reindex running',
    })
    expect(screen.getByRole('alert')).toHaveTextContent(
      /reindex is running, retry in a minute\./i,
    )
  })

  it('on 500 with no JSON body: falls back to generic non-mapped error message', () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(500, undefined)
    const alert = screen.getByRole('alert')
    expect(alert).toBeInTheDocument()
    expect(alert).toHaveTextContent(/error|500/i)
    expect(alert).not.toHaveTextContent(/ollama is unavailable/i)
    expect(alert).not.toHaveTextContent(/reindex is running/i)
    expect(alert).not.toHaveTextContent(/file is corrupted/i)
  })

  it('on 401: calls authStore.logout() and does not show error alert', () => {
    const logoutSpy = vi.spyOn(useAuthStore.getState(), 'logout')
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(401, { error: 'unauthorized' })
    expect(logoutSpy).toHaveBeenCalledTimes(1)
  })

  it('disabled while isReindexing=true: drop is a no-op', () => {
    renderUpload({ isReindexing: true })
    dropFile(dropZone(), makeFile('a.docx'))
    expect(createdXhrs).toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('error panel can be dismissed via the close button, returning to idle', async () => {
    renderUpload()
    dropFile(dropZone(), makeFile('a.docx'))
    respondWith(503, { error: 'reindex_in_progress', message: 'busy' })

    expect(screen.getByRole('alert')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /dismiss error/i }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
