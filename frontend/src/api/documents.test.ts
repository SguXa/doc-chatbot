import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fetchDocuments } from './documents'
import { useAuthStore } from '@/stores/authStore'

function mockFetch(response: Response) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)
}

describe('api/documents', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  describe('fetchDocuments', () => {
    it('GETs /api/admin/documents and returns the parsed shape', async () => {
      const payload = {
        documents: [
          {
            id: 1,
            filename: 'guide.docx',
            fileType: 'docx',
            fileSize: 12345,
            fileHash: 'abc',
            chunkCount: 42,
            imageCount: 3,
            indexedAt: '2026-04-27 10:00:00',
            createdAt: '2026-04-27 10:00:00',
          },
        ],
        total: 1,
      }
      mockFetch(new Response(JSON.stringify(payload), { status: 200 }))

      const result = await fetchDocuments()

      const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
      expect(call[0]).toBe('/api/admin/documents')
      expect((call[1] as RequestInit).method).toBe('GET')
      expect(result).toEqual(payload)
    })

    it('preserves server order in the documents array', async () => {
      const payload = {
        documents: [
          { id: 2, filename: 'b.pdf', fileType: 'pdf', fileSize: 1, fileHash: 'b', chunkCount: 0, imageCount: 0, indexedAt: '2026-04-27 11:00:00', createdAt: '2026-04-27 11:00:00' },
          { id: 1, filename: 'a.docx', fileType: 'docx', fileSize: 1, fileHash: 'a', chunkCount: 0, imageCount: 0, indexedAt: '2026-04-27 10:00:00', createdAt: '2026-04-27 10:00:00' },
        ],
        total: 2,
      }
      mockFetch(new Response(JSON.stringify(payload), { status: 200 }))

      const result = await fetchDocuments()

      expect(result.documents.map((d) => d.id)).toEqual([2, 1])
    })
  })
})
