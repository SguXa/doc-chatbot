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
  try {
    const response = await fetch(`${BASE_URL}/api/health/ready`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
    })
    const body = (await response.json()) as Partial<ReadyStatus> | null
    const status = body?.backfill?.status
    if (status === 'idle' || status === 'running' || status === 'ready' || status === 'failed') {
      return { backfill: { status } }
    }
    return { backfill: { status: 'idle' } }
  } catch {
    return { backfill: { status: 'idle' } }
  }
}

function reindex(): Promise<ReindexResponse> {
  return apiPost<ReindexResponse>('/api/admin/reindex')
}

function deleteDocument(id: number): Promise<void> {
  return apiDelete<void>(`/api/admin/documents/${id}`)
}

export { fetchReady, reindex, deleteDocument }
export type { ReadyStatus, ReindexResponse, BackfillStatus }
