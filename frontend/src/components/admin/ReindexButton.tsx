import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { RotateCw } from 'lucide-react'
import { reindex } from '@/api/admin'
import type { ReadyStatus } from '@/api/admin'
import { useReadyStatus } from '@/hooks/useReadyStatus'
import { parseApiError } from '@/lib/errors'
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
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'

function ReindexButton() {
  const { isRunning } = useReadyStatus()
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: reindex,
    onSuccess: () => {
      toast.success('Reindex started')
      // Optimistically reflect "running" locally instead of invalidating.
      // The backend launches the reindex in a coroutine after returning 202,
      // so an immediate refetch can race the coroutine startup and observe
      // the prior idle/completed status — which would leave polling off
      // and re-enable mutating controls mid-reindex.
      queryClient.setQueryData<ReadyStatus>(['ready'], { backfill: { status: 'running' } })
    },
    onError: (error) => {
      const parsed = parseApiError(error)
      if (parsed.kind === 'reindex_in_progress') {
        toast.success('Reindex started')
        queryClient.setQueryData<ReadyStatus>(['ready'], { backfill: { status: 'running' } })
        return
      }
      toast.error(parsed.message)
    },
  })

  const disabled = isRunning || mutation.isPending

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button
          variant="outline"
          disabled={disabled}
          title={isRunning ? 'Reindex is running' : undefined}
        >
          <RotateCw />
          Reindex all
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Reindex all documents?</AlertDialogTitle>
          <AlertDialogDescription>
            This will regenerate embeddings for every document in the knowledge
            base. The chat endpoint will continue to work using current
            embeddings until reindex completes.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={() => mutation.mutate()}>
            Confirm
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

export { ReindexButton }
