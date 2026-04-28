import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Trash2 } from 'lucide-react'
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { deleteDocument } from '@/api/admin'
import { parseApiError } from '@/lib/errors'
import type { DocumentDto } from '@/api/documents'

const KB = 1024
const MB = KB * 1024
const GB = MB * 1024

function formatFileSize(bytes: number): string {
  if (bytes < KB) return `${bytes} B`
  if (bytes < MB) return `${(bytes / KB).toFixed(1)} KB`
  if (bytes < GB) return `${(bytes / MB).toFixed(1)} MB`
  return `${(bytes / GB).toFixed(1)} GB`
}

const RELATIVE_THRESHOLDS: Array<[number, Intl.RelativeTimeFormatUnit]> = [
  [60, 'second'],
  [60 * 60, 'minute'],
  [60 * 60 * 24, 'hour'],
  [60 * 60 * 24 * 30, 'day'],
  [60 * 60 * 24 * 365, 'month'],
]

function formatRelativeTime(iso: string, now: Date = new Date()): string {
  const parsed = parseSqliteTimestamp(iso)
  if (parsed === null) return iso
  const diffSec = (parsed - now.getTime()) / 1000

  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })
  const absSec = Math.abs(diffSec)

  for (const [threshold, unit] of RELATIVE_THRESHOLDS) {
    if (absSec < threshold) {
      const divisor =
        unit === 'second' ? 1
        : unit === 'minute' ? 60
        : unit === 'hour' ? 3600
        : unit === 'day' ? 86400
        : 2592000
      return formatter.format(Math.round(diffSec / divisor), unit)
    }
  }
  return formatter.format(Math.round(diffSec / (60 * 60 * 24 * 365)), 'year')
}

function parseSqliteTimestamp(value: string): number | null {
  if (!value) return null
  const isoCandidate = value.includes('T') ? value : value.replace(' ', 'T') + 'Z'
  const ms = Date.parse(isoCandidate)
  return Number.isNaN(ms) ? null : ms
}

interface DocumentTableProps {
  documents: DocumentDto[]
  isReindexing?: boolean
}

function DocumentTable({ documents, isReindexing = false }: DocumentTableProps) {
  const [pendingDelete, setPendingDelete] = useState<DocumentDto | null>(null)
  const queryClient = useQueryClient()

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteDocument(id),
    onSuccess: () => {
      toast.success('Deleted')
      queryClient.invalidateQueries({ queryKey: ['documents'] })
      setPendingDelete(null)
    },
    onError: (error) => {
      const parsed = parseApiError(error)
      if (parsed.kind === 'reindex_in_progress') {
        // Self-heal a stale readiness view: server says reindex is running,
        // so the UI's gate should be active even if our last poll missed it.
        queryClient.invalidateQueries({ queryKey: ['ready'] })
      }
      toast.error(parsed.message)
      setPendingDelete(null)
    },
  })

  return (
    <>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Filename</TableHead>
            <TableHead>Type</TableHead>
            <TableHead>Size</TableHead>
            <TableHead>Chunks</TableHead>
            <TableHead>Images</TableHead>
            <TableHead>Indexed at</TableHead>
            <TableHead className="w-12"></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {documents.map((doc) => (
            <TableRow key={doc.id}>
              <TableCell className="font-medium">{doc.filename}</TableCell>
              <TableCell>
                <span className="rounded-md bg-muted px-2 py-0.5 text-xs uppercase">
                  {doc.fileType}
                </span>
              </TableCell>
              <TableCell>{formatFileSize(doc.fileSize)}</TableCell>
              <TableCell>{doc.chunkCount}</TableCell>
              <TableCell>{doc.imageCount}</TableCell>
              <TableCell title={doc.indexedAt}>{formatRelativeTime(doc.indexedAt)}</TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Delete ${doc.filename}`}
                  disabled={isReindexing || deleteMutation.isPending}
                  title={isReindexing ? 'Reindex is running' : undefined}
                  onClick={() => setPendingDelete(doc)}
                >
                  <Trash2 />
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <AlertDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Delete {pendingDelete?.filename}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              Chunks and images will be removed.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={(event) => {
                event.preventDefault()
                if (pendingDelete) deleteMutation.mutate(pendingDelete.id)
              }}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

export { DocumentTable }
