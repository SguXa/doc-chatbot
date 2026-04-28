import { apiPost, apiDelete } from './client'

type BackfillStatus = 'idle' | 'running' | 'ready' | 'failed'

interface ReadyStatus {
  backfill: { status: BackfillStatus }
}

interface ReindexResponse {
  status: 'started' | 'already_running'
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

async function fetchReady(): Promise<ReadyStatus> {
  // Errors are intentionally re-thrown: TanStack Query keeps the previous
  // successful data on failure, so a transient blip during a running
  // reindex stays "running" and polling continues at the same interval.
  // Falling back to idle here would silently drop polling and re-enable
  // mutating controls mid-reindex.
  const response = await fetch(`${BASE_URL}/api/health/ready`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
  })
  const body = (await response.json()) as Partial<ReadyStatus> | null
  const status = body?.backfill?.status
  if (status === 'idle' || status === 'running' || status === 'ready' || status === 'failed') {
    return { backfill: { status } }
  }
  throw new Error('Invalid /api/health/ready response shape')
}

function reindex(): Promise<ReindexResponse> {
  return apiPost<ReindexResponse>('/api/admin/reindex')
}

function deleteDocument(id: number): Promise<void> {
  return apiDelete<void>(`/api/admin/documents/${id}`)
}

export { fetchReady, reindex, deleteDocument }
export type { ReadyStatus, ReindexResponse, BackfillStatus }
