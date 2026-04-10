import { describe, it, expect, vi, beforeEach } from 'vitest'
import { apiGet, apiPost, ApiError } from './client'

describe('api/client', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  describe('apiGet', () => {
    it('constructs URL with path and returns parsed JSON', async () => {
      const mockData = { status: 'healthy' }
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(JSON.stringify(mockData), { status: 200 }),
      )

      const result = await apiGet('/api/health')

      expect(globalThis.fetch).toHaveBeenCalledWith('/api/health', {
        headers: { 'Accept': 'application/json' },
      })
      expect(result).toEqual(mockData)
    })

    it('throws ApiError on non-ok response', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('Not Found', { status: 404, statusText: 'Not Found' }),
      )

      await expect(apiGet('/api/missing')).rejects.toThrow(ApiError)
      await expect(apiGet('/api/missing')).rejects.toThrow('404')
    })
  })

  describe('apiPost', () => {
    it('sends POST with JSON body and returns parsed response', async () => {
      const requestBody = { name: 'test' }
      const responseData = { id: 1 }
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(JSON.stringify(responseData), { status: 200 }),
      )

      const result = await apiPost('/api/resource', requestBody)

      expect(globalThis.fetch).toHaveBeenCalledWith('/api/resource', {
        method: 'POST',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(requestBody),
      })
      expect(result).toEqual(responseData)
    })

    it('throws ApiError on error response', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('Server Error', { status: 500, statusText: 'Internal Server Error' }),
      )

      await expect(apiPost('/api/resource', {})).rejects.toThrow(ApiError)
    })
  })
})
