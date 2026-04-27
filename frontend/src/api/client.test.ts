import { describe, it, expect, vi, beforeEach } from 'vitest'
import { apiGet, apiPost, apiPut, apiDelete, ApiError, UnauthorizedError } from './client'
import { useAuthStore } from '@/stores/authStore'

function mockFetch(response: Response) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)
}

function getAuthHeader(): string | null {
  const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
  const init = call[1] as RequestInit
  const headers = init.headers as Headers
  return headers.get('Authorization')
}

describe('api/client', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  describe('apiGet', () => {
    it('constructs URL with path and returns parsed JSON', async () => {
      const mockData = { status: 'healthy' }
      mockFetch(new Response(JSON.stringify(mockData), { status: 200 }))

      const result = await apiGet('/api/health')

      expect(globalThis.fetch).toHaveBeenCalled()
      const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
      expect(call[0]).toBe('/api/health')
      expect((call[1] as RequestInit).method).toBe('GET')
      expect(result).toEqual(mockData)
    })

    it('throws ApiError on non-ok response', async () => {
      mockFetch(new Response('Not Found', { status: 404, statusText: 'Not Found' }))

      try {
        await apiGet('/api/missing')
        expect.unreachable('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        expect((error as ApiError).status).toBe(404)
        expect((error as ApiError).message).toContain('404')
      }
    })
  })

  describe('apiPost', () => {
    it('sends POST with JSON body and returns parsed response', async () => {
      const requestBody = { name: 'test' }
      const responseData = { id: 1 }
      mockFetch(new Response(JSON.stringify(responseData), { status: 200 }))

      const result = await apiPost('/api/resource', requestBody)

      const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
      const init = call[1] as RequestInit
      expect(call[0]).toBe('/api/resource')
      expect(init.method).toBe('POST')
      expect((init.headers as Headers).get('Content-Type')).toBe('application/json')
      expect(init.body).toBe(JSON.stringify(requestBody))
      expect(result).toEqual(responseData)
    })

    it('throws ApiError on error response', async () => {
      mockFetch(new Response('Server Error', { status: 500, statusText: 'Internal Server Error' }))

      await expect(apiPost('/api/resource', {})).rejects.toThrow(ApiError)
    })
  })

  describe('Authorization header injection', () => {
    it('apiGet includes Authorization header when token is present', async () => {
      useAuthStore.getState().login('test-token')
      mockFetch(new Response(JSON.stringify({}), { status: 200 }))

      await apiGet('/api/admin/documents')

      expect(getAuthHeader()).toBe('Bearer test-token')
    })

    it('apiPost includes Authorization header when token is present', async () => {
      useAuthStore.getState().login('test-token')
      mockFetch(new Response(JSON.stringify({}), { status: 200 }))

      await apiPost('/api/admin/reindex')

      expect(getAuthHeader()).toBe('Bearer test-token')
    })

    it('apiPut includes Authorization header when token is present', async () => {
      useAuthStore.getState().login('test-token')
      mockFetch(new Response(JSON.stringify({}), { status: 200 }))

      await apiPut('/api/config/system-prompt', { prompt: 'x' })

      expect(getAuthHeader()).toBe('Bearer test-token')
    })

    it('apiDelete includes Authorization header when token is present', async () => {
      useAuthStore.getState().login('test-token')
      mockFetch(new Response(null, { status: 204 }))

      await apiDelete('/api/admin/documents/1')

      expect(getAuthHeader()).toBe('Bearer test-token')
    })

    it('omits Authorization header when token is absent', async () => {
      mockFetch(new Response(JSON.stringify({}), { status: 200 }))

      await apiGet('/api/health')

      expect(getAuthHeader()).toBeNull()
    })
  })

  describe('401 handling', () => {
    it('throws UnauthorizedError, clears store, and removes localStorage on 401', async () => {
      useAuthStore.getState().login('expired-token')
      expect(localStorage.getItem('aos.token')).toBe('expired-token')
      expect(useAuthStore.getState().isAuthenticated).toBe(true)

      mockFetch(new Response(JSON.stringify({ error: 'invalid_token' }), { status: 401 }))

      await expect(apiGet('/api/admin/documents')).rejects.toBeInstanceOf(UnauthorizedError)
      expect(useAuthStore.getState().isAuthenticated).toBe(false)
      expect(useAuthStore.getState().token).toBeNull()
      expect(localStorage.getItem('aos.token')).toBeNull()
    })

    it('UnauthorizedError is also an ApiError with status 401', async () => {
      mockFetch(new Response('', { status: 401, statusText: 'Unauthorized' }))

      try {
        await apiGet('/api/admin/documents')
        expect.unreachable('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(UnauthorizedError)
        expect(error).toBeInstanceOf(ApiError)
        expect((error as ApiError).status).toBe(401)
      }
    })
  })

  describe('error body parsing', () => {
    it('attaches parsed JSON body on 4xx', async () => {
      mockFetch(
        new Response(
          JSON.stringify({ error: 'duplicate_document', existing: { id: 1 } }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        ),
      )

      try {
        await apiPost('/api/admin/documents')
        expect.unreachable('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        expect((error as ApiError).body).toEqual({
          error: 'duplicate_document',
          existing: { id: 1 },
        })
      }
    })

    it('leaves body undefined when 4xx body is non-JSON', async () => {
      mockFetch(new Response('plain text error', { status: 400, statusText: 'Bad Request' }))

      try {
        await apiPost('/api/admin/documents')
        expect.unreachable('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        expect((error as ApiError).body).toBeUndefined()
      }
    })

    it('attaches parsed JSON body on 5xx', async () => {
      mockFetch(
        new Response(
          JSON.stringify({ error: 'ollama_unavailable' }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        ),
      )

      try {
        await apiPost('/api/admin/documents')
        expect.unreachable('Should have thrown')
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError)
        expect((error as ApiError).status).toBe(503)
        expect((error as ApiError).body).toEqual({ error: 'ollama_unavailable' })
      }
    })
  })

  describe('204 No Content handling', () => {
    it('apiGet returns undefined on 204', async () => {
      mockFetch(new Response(null, { status: 204 }))

      const result = await apiGet('/api/something')

      expect(result).toBeUndefined()
    })

    it('apiPost returns undefined on 204', async () => {
      mockFetch(new Response(null, { status: 204 }))

      const result = await apiPost('/api/auth/logout')

      expect(result).toBeUndefined()
    })

    it('apiPut returns undefined on 204', async () => {
      mockFetch(new Response(null, { status: 204 }))

      const result = await apiPut('/api/something', {})

      expect(result).toBeUndefined()
    })

    it('apiDelete returns undefined on 204', async () => {
      mockFetch(new Response(null, { status: 204 }))

      const result = await apiDelete('/api/admin/documents/1')

      expect(result).toBeUndefined()
    })
  })

  describe('apiPut and apiDelete basics', () => {
    it('apiPut sends PUT with JSON body and returns parsed response', async () => {
      const responseData = { prompt: 'updated', updatedAt: '2026-04-27 10:00:00' }
      mockFetch(new Response(JSON.stringify(responseData), { status: 200 }))

      const result = await apiPut('/api/config/system-prompt', { prompt: 'updated' })

      const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
      const init = call[1] as RequestInit
      expect(call[0]).toBe('/api/config/system-prompt')
      expect(init.method).toBe('PUT')
      expect((init.headers as Headers).get('Content-Type')).toBe('application/json')
      expect(init.body).toBe(JSON.stringify({ prompt: 'updated' }))
      expect(result).toEqual(responseData)
    })

    it('apiDelete sends DELETE without a body', async () => {
      mockFetch(new Response(null, { status: 204 }))

      await apiDelete('/api/admin/documents/42')

      const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
      const init = call[1] as RequestInit
      expect(call[0]).toBe('/api/admin/documents/42')
      expect(init.method).toBe('DELETE')
      expect(init.body).toBeUndefined()
    })
  })
})
