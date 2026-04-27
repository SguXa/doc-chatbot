import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fetchSystemPrompt, updateSystemPrompt } from './config'
import { useAuthStore } from '@/stores/authStore'

function mockFetch(response: Response) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)
}

describe('api/config', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  it('fetchSystemPrompt GETs /api/config/system-prompt', async () => {
    const payload = { prompt: 'You are an assistant', updatedAt: '2026-04-27 10:00:00' }
    mockFetch(new Response(JSON.stringify(payload), { status: 200 }))

    const result = await fetchSystemPrompt()

    const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
    expect(call[0]).toBe('/api/config/system-prompt')
    expect((call[1] as RequestInit).method).toBe('GET')
    expect(result).toEqual(payload)
  })

  it('updateSystemPrompt PUTs /api/config/system-prompt with prompt body', async () => {
    const payload = { prompt: 'new prompt', updatedAt: '2026-04-27 11:00:00' }
    mockFetch(new Response(JSON.stringify(payload), { status: 200 }))

    const result = await updateSystemPrompt('new prompt')

    const call = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]
    expect(call[0]).toBe('/api/config/system-prompt')
    const init = call[1] as RequestInit
    expect(init.method).toBe('PUT')
    expect(init.body).toBe(JSON.stringify({ prompt: 'new prompt' }))
    expect(result).toEqual(payload)
  })
})
