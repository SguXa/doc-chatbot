import { apiGet } from './client'
import type { DocumentDto } from '@/lib/errors'

interface DocumentListResponse {
  documents: DocumentDto[]
  total: number
}

function fetchDocuments(): Promise<DocumentListResponse> {
  return apiGet<DocumentListResponse>('/api/admin/documents')
}

export { fetchDocuments }
export type { DocumentDto, DocumentListResponse }
