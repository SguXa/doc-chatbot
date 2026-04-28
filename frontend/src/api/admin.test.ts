import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fetchReady, reindex, deleteDocument } from './admin'
import { useAuthStore } from '@/stores/authStore'

function mockFetch(response: Response) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)
}

function lastFetchCall(): { url: string; init: RequestInit } {
  const calls = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls
  const call = calls[calls.length - 1]
  return { url: call[0] as string, init: call[1] as RequestInit }
}

describe('api/admin', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  describe('fetchReady', () => {
    it('GETs /api/health/ready and returns the parsed body on 200', async () => {
      mockFetch(
        new Response(JSON.stringify({ backfill: { status: 'idle' }, status: 'ready' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const result = await fetchReady()

      expect(lastFetchCall().url).toBe('/api/health/ready')
      expect(lastFetchCall().init.method).toBe('GET')
      expect(result).toEqual({ backfill: { status: 'idle' } })
    })

    it('parses the body of a 503 response (running backfill)', async () => {
      mockFetch(
        new Response(JSON.stringify({ backfill: { status: 'running' } }), {
          status: 503,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      const result = await fetchReady()

      expect(result.backfill.status).toBe('running')
    })

    it('does not send Authorization header (public endpoint)', async () => {
      useAuthStore.getState().login('some-token')
      mockFetch(
        new Response(JSON.stringify({ backfill: { status: 'idle' } }), { status: 200 }),
      )

      await fetchReady()

      const headers = lastFetchCall().init.headers as Record<string, string>
      expect(headers).not.toHaveProperty('Authorization')
    })

    it('rethrows on network failure so TanStack Query keeps last data', async () => {
      vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'))

      await expect(fetchReady()).rejects.toThrow(/offline/)
    })

    it('rethrows on non-JSON body so polling can keep last known status', async () => {
      mockFetch(new Response('not json', { status: 200 }))

      await expect(fetchReady()).rejects.toThrow()
    })

    it('rethrows when body has an unrecognized status field', async () => {
      mockFetch(
        new Response(JSON.stringify({ backfill: { status: 'mystery' } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

      await expect(fetchReady()).rejects.toThrow(
        /Invalid \/api\/health\/ready response shape/,
      )
    })
  })

  describe('reindex', () => {
    it('POSTs /api/admin/reindex and returns parsed response', async () => {
      mockFetch(new Response(JSON.stringify({ status: 'started' }), { status: 202 }))

      const result = await reindex()

      expect(lastFetchCall().url).toBe('/api/admin/reindex')
      expect(lastFetchCall().init.method).toBe('POST')
      expect(result).toEqual({ status: 'started' })
    })
  })

  describe('deleteDocument', () => {
    it('DELETEs /api/admin/documents/:id', async () => {
      mockFetch(new Response(null, { status: 204 }))

      await deleteDocument(42)

      expect(lastFetchCall().url).toBe('/api/admin/documents/42')
      expect(lastFetchCall().init.method).toBe('DELETE')
    })
  })
})
