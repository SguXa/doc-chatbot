import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DocumentsPage } from './DocumentsPage'
import * as documentsApi from '@/api/documents'
import * as adminApi from '@/api/admin'
import * as sonner from 'sonner'
import { ApiError } from '@/api/client'
import type { DocumentDto } from '@/api/documents'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <DocumentsPage />
      </QueryClientProvider>,
    ),
  }
}

const SAMPLE_DOC_A: DocumentDto = {
  id: 1,
  filename: 'a.docx',
  fileType: 'docx',
  fileSize: 2048,
  fileHash: 'a',
  chunkCount: 10,
  imageCount: 2,
  indexedAt: '2026-04-27 09:00:00',
  createdAt: '2026-04-27 09:00:00',
}

const SAMPLE_DOC_B: DocumentDto = {
  id: 2,
  filename: 'b.pdf',
  fileType: 'pdf',
  fileSize: 1024 * 1024 * 3,
  fileHash: 'b',
  chunkCount: 25,
  imageCount: 0,
  indexedAt: '2026-04-27 10:00:00',
  createdAt: '2026-04-27 10:00:00',
}

describe('DocumentsPage', () => {
  beforeEach(() => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue({
      backfill: { status: 'idle' },
    })
    vi.spyOn(sonner.toast, 'success').mockImplementation(() => '' as never)
    vi.spyOn(sonner.toast, 'error').mockImplementation(() => '' as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the loading message initially', async () => {
    let resolve!: (v: { documents: DocumentDto[]; total: number }) => void
    vi.spyOn(documentsApi, 'fetchDocuments').mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )

    renderPage()

    expect(screen.getByText(/loading documents/i)).toBeInTheDocument()

    resolve({ documents: [], total: 0 })
    await waitFor(() => {
      expect(screen.queryByText(/loading documents/i)).not.toBeInTheDocument()
    })
  })

  it('shows the empty state when the API returns zero documents', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({ documents: [], total: 0 })

    renderPage()

    expect(
      await screen.findByText(/no documents\. upload your first/i),
    ).toBeInTheDocument()
  })

  it('renders a table with one row per document when the API returns documents', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({
      documents: [SAMPLE_DOC_B, SAMPLE_DOC_A],
      total: 2,
    })

    renderPage()

    expect(await screen.findByText('a.docx')).toBeInTheDocument()
    expect(screen.getByText('b.pdf')).toBeInTheDocument()
    const rows = screen.getAllByRole('row')
    expect(rows).toHaveLength(3)
  })

  it('preserves server order in the rendered table (no client re-sort)', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({
      documents: [SAMPLE_DOC_B, SAMPLE_DOC_A],
      total: 2,
    })

    renderPage()

    await screen.findByText('a.docx')
    const bodyRows = screen.getAllByRole('row').slice(1)
    expect(within(bodyRows[0]).getByText('b.pdf')).toBeInTheDocument()
    expect(within(bodyRows[1]).getByText('a.docx')).toBeInTheDocument()
  })

  it('shows an error card with parsed message when the API throws ApiError', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockRejectedValue(
      new ApiError(503, 'Service Unavailable', { error: 'reindex_in_progress' }),
    )

    renderPage()

    expect(await screen.findByText(/failed to load documents/i)).toBeInTheDocument()
    expect(screen.getAllByText(/reindex is running/i).length).toBeGreaterThan(0)
  })

  it('renders the Reindex button alongside the page header', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({ documents: [], total: 0 })

    renderPage()

    expect(
      await screen.findByRole('button', { name: /reindex all/i }),
    ).toBeInTheDocument()
  })

  it('opens delete confirmation dialog and calls deleteDocument on confirm', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({
      documents: [SAMPLE_DOC_A],
      total: 1,
    })
    const deleteSpy = vi.spyOn(adminApi, 'deleteDocument').mockResolvedValue(undefined)

    const { queryClient } = renderPage()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await screen.findByText('a.docx')
    const deleteButton = screen.getByRole('button', { name: /delete a\.docx/i })
    fireEvent.click(deleteButton)

    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByText(/delete a\.docx\?/i)).toBeInTheDocument()

    const confirmButton = screen.getByRole('button', { name: /^delete$/i })
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(deleteSpy).toHaveBeenCalledWith(1)
    })
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['documents'] })
    })
    await waitFor(() => {
      expect(sonner.toast.success).toHaveBeenCalledWith('Deleted')
    })
  })

  it('shows parsed error message when delete returns 503 reindex_in_progress', async () => {
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({
      documents: [SAMPLE_DOC_A],
      total: 1,
    })
    vi.spyOn(adminApi, 'deleteDocument').mockRejectedValue(
      new ApiError(503, 'Service Unavailable', { error: 'reindex_in_progress' }),
    )

    renderPage()

    await screen.findByText('a.docx')
    fireEvent.click(screen.getByRole('button', { name: /delete a\.docx/i }))
    fireEvent.click(await screen.findByRole('button', { name: /^delete$/i }))

    await waitFor(() => {
      expect(sonner.toast.error).toHaveBeenCalled()
    })
    const errorMessage = (sonner.toast.error as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(errorMessage).toMatch(/reindex is running/i)
  })

  it('disables row delete buttons while reindex is running', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue({
      backfill: { status: 'running' },
    })
    vi.spyOn(documentsApi, 'fetchDocuments').mockResolvedValue({
      documents: [SAMPLE_DOC_A],
      total: 1,
    })

    renderPage()

    await screen.findByText('a.docx')
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /delete a\.docx/i }),
      ).toBeDisabled()
    })
  })
})
