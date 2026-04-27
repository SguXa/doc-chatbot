import { useQuery } from '@tanstack/react-query'
import { fetchReady } from '@/api/admin'
import type { BackfillStatus, ReadyStatus } from '@/api/admin'

interface UseReadyStatusResult {
  status: BackfillStatus
  isRunning: boolean
}

function useReadyStatus(): UseReadyStatusResult {
  const { data } = useQuery<ReadyStatus>({
    queryKey: ['ready'],
    queryFn: fetchReady,
    refetchInterval: (query) =>
      query.state.data?.backfill.status === 'running' ? 3000 : false,
    retry: false,
  })

  const status = data?.backfill.status ?? 'idle'
  return { status, isRunning: status === 'running' }
}

export { useReadyStatus }
export type { UseReadyStatusResult }
