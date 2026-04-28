import { useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Loader2, Upload, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useAuthStore } from '@/stores/authStore'
import { ApiError } from '@/api/client'
import { parseApiError, type ParsedError, type DocumentDto } from '@/lib/errors'
import { cn } from '@/lib/utils'

const ACCEPTED_EXTENSIONS = ['.docx', '.pdf'] as const
const ACCEPT_ATTR = ACCEPTED_EXTENSIONS.join(',')
const UPLOAD_URL = '/api/admin/documents'

type UploadStatus = 'idle' | 'uploading' | 'parsing' | 'error' | 'duplicate'

interface DocumentUploadProps {
  isReindexing?: boolean
}

function hasAcceptedExtension(filename: string): boolean {
  const lower = filename.toLowerCase()
  return ACCEPTED_EXTENSIONS.some((ext) => lower.endsWith(ext))
}

function buildApiErrorFromXhr(xhr: XMLHttpRequest): ApiError {
  let body: unknown = undefined
  if (typeof xhr.response === 'string' && xhr.response.length > 0) {
    try {
      body = JSON.parse(xhr.response)
    } catch {
      body = undefined
    }
  } else if (xhr.response && typeof xhr.response === 'object') {
    body = xhr.response
  }
  return new ApiError(xhr.status, xhr.statusText || '', body)
}

function DocumentUpload({ isReindexing = false }: DocumentUploadProps) {
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const xhrRef = useRef<XMLHttpRequest | null>(null)
  const [status, setStatus] = useState<UploadStatus>('idle')
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState<ParsedError | null>(null)
  const [duplicate, setDuplicate] = useState<DocumentDto | null>(null)
  const [isDragOver, setIsDragOver] = useState(false)

  const isBusy = status === 'uploading' || status === 'parsing'
  const isDisabled = isReindexing || isBusy

  function reset() {
    setStatus('idle')
    setProgress(0)
    setError(null)
    setDuplicate(null)
    xhrRef.current = null
  }

  function handleClientRejection(message: string) {
    setStatus('error')
    setError({ kind: 'unsupported_extension', message })
    toast.error(message)
  }

  function startUpload(file: File) {
    if (!hasAcceptedExtension(file.name)) {
      handleClientRejection('Only .docx and .pdf are supported')
      return
    }

    setStatus('uploading')
    setProgress(0)
    setError(null)
    setDuplicate(null)

    const xhr = new XMLHttpRequest()
    xhrRef.current = xhr
    xhr.open('POST', UPLOAD_URL)

    const token = useAuthStore.getState().token
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    }
    xhr.setRequestHeader('Accept', 'application/json')

    xhr.upload.onprogress = (event: ProgressEvent) => {
      if (event.lengthComputable && event.total > 0) {
        const pct = Math.min(100, Math.round((event.loaded / event.total) * 100))
        setProgress(pct)
      }
    }
    xhr.upload.onload = () => {
      setProgress(100)
      setStatus('parsing')
    }

    xhr.onload = () => {
      xhrRef.current = null
      if (xhr.status === 201) {
        toast.success('Uploaded')
        queryClient.invalidateQueries({ queryKey: ['documents'] })
        reset()
        return
      }
      if (xhr.status === 401) {
        // ProtectedRoute will redirect on the next render; reset() so the
        // upload zone isn't left in 'parsing' state if it briefly stays mounted.
        reset()
        useAuthStore.getState().logout()
        return
      }
      const apiError = buildApiErrorFromXhr(xhr)
      const parsed = parseApiError(apiError)
      if (parsed.kind === 'duplicate' && parsed.existing) {
        setStatus('duplicate')
        setDuplicate(parsed.existing)
        setError(parsed)
        toast.error(parsed.message)
        return
      }
      if (parsed.kind === 'reindex_in_progress') {
        // Server tells us a reindex is running; refresh the readiness query
        // so the UI catches up if it had a stale view of backfill status.
        queryClient.invalidateQueries({ queryKey: ['ready'] })
      }
      setStatus('error')
      setError(parsed)
      toast.error(parsed.message)
    }

    xhr.onerror = () => {
      xhrRef.current = null
      setStatus('error')
      const fallback: ParsedError = {
        kind: 'unknown',
        message: 'Network error. Please retry.',
      }
      setError(fallback)
      toast.error(fallback.message)
    }

    const formData = new FormData()
    formData.append('file', file)
    xhr.send(formData)
  }

  function handleFileSelected(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (file) startUpload(file)
    event.target.value = ''
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragOver(false)
    if (isDisabled) return
    const file = event.dataTransfer.files?.[0]
    if (file) startUpload(file)
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    if (isDisabled) return
    setIsDragOver(true)
  }

  function handleDragEnter(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    if (isDisabled) return
    setIsDragOver(true)
  }

  function handleDragLeave(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setIsDragOver(false)
  }

  function handleZoneClick() {
    if (isDisabled) return
    fileInputRef.current?.click()
  }

  const tooltip = isReindexing ? 'Reindex is running' : undefined

  return (
    <div className="space-y-3">
      <Card
        role="button"
        tabIndex={isDisabled ? -1 : 0}
        aria-label="Upload document"
        aria-disabled={isDisabled}
        title={tooltip}
        onClick={handleZoneClick}
        onKeyDown={(e) => {
          if ((e.key === 'Enter' || e.key === ' ') && !isDisabled) {
            e.preventDefault()
            handleZoneClick()
          }
        }}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragEnter={handleDragEnter}
        onDragLeave={handleDragLeave}
        className={cn(
          'flex flex-col items-center justify-center gap-2 border-2 border-dashed py-10 text-center transition-colors',
          isDragOver && !isDisabled && 'border-primary bg-muted/50',
          isDisabled && 'opacity-60 cursor-not-allowed',
          !isDisabled && 'cursor-pointer hover:bg-muted/30',
        )}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept={ACCEPT_ATTR}
          className="hidden"
          onChange={handleFileSelected}
          aria-label="File input"
        />
        {status === 'uploading' && (
          <div className="w-full max-w-sm space-y-2 px-6">
            <div className="flex items-center gap-2 justify-center text-sm text-muted-foreground">
              <Upload className="size-4" />
              <span>Uploading… {progress}%</span>
            </div>
            <div
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={progress}
              className="h-2 w-full overflow-hidden rounded-full bg-muted"
            >
              <div
                className="h-full bg-primary transition-all"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>
        )}
        {status === 'parsing' && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            <span>Parsing document…</span>
          </div>
        )}
        {status !== 'uploading' && status !== 'parsing' && (
          <>
            <Upload className="size-6 text-muted-foreground" />
            <p className="text-sm font-medium">
              Drop a .docx or .pdf here, or click to browse
            </p>
            <p className="text-xs text-muted-foreground">Max 100 MB</p>
          </>
        )}
      </Card>

      {error && status === 'error' && (
        <div
          role="alert"
          className="flex items-start justify-between gap-3 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
        >
          <span>{error.message}</span>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="Dismiss error"
            onClick={reset}
          >
            <X />
          </Button>
        </div>
      )}

      <Dialog
        open={status === 'duplicate'}
        onOpenChange={(open) => {
          if (!open) reset()
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Document already indexed</DialogTitle>
            <DialogDescription>
              A document with identical content has already been uploaded.
            </DialogDescription>
          </DialogHeader>
          {duplicate && (
            <dl className="grid grid-cols-[auto,1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-muted-foreground">Filename</dt>
              <dd className="font-medium">{duplicate.filename}</dd>
              <dt className="text-muted-foreground">Indexed at</dt>
              <dd>{duplicate.indexedAt}</dd>
            </dl>
          )}
          <DialogFooter>
            <Button onClick={reset}>Got it</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

export { DocumentUpload }
