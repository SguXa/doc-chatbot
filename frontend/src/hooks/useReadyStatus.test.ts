import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as adminApi from '@/api/admin'
import { useReadyStatus } from './useReadyStatus'
import type { ReadyStatus } from '@/api/admin'
import type { ReactNode } from 'react'
import { createElement } from 'react'

function makeWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
  return { Wrapper, queryClient }
}

describe('useReadyStatus', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('returns status and isRunning derived from fetchReady', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue({
      backfill: { status: 'idle' },
    } as ReadyStatus)
    const { Wrapper } = makeWrapper()

    const { result } = renderHook(() => useReadyStatus(), { wrapper: Wrapper })

    await vi.waitFor(() => {
      expect(result.current.status).toBe('idle')
      expect(result.current.isRunning).toBe(false)
    })
  })

  it('reports isRunning=true while backfill is running', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue({
      backfill: { status: 'running' },
    } as ReadyStatus)
    const { Wrapper } = makeWrapper()

    const { result } = renderHook(() => useReadyStatus(), { wrapper: Wrapper })

    await vi.waitFor(() => {
      expect(result.current.isRunning).toBe(true)
    })
  })

  it('polls every 3000ms while running and stops once idle', async () => {
    const fetchReadySpy = vi.spyOn(adminApi, 'fetchReady')
    fetchReadySpy.mockResolvedValueOnce({
      backfill: { status: 'running' },
    } as ReadyStatus)
    fetchReadySpy.mockResolvedValueOnce({
      backfill: { status: 'running' },
    } as ReadyStatus)
    fetchReadySpy.mockResolvedValueOnce({
      backfill: { status: 'idle' },
    } as ReadyStatus)
    fetchReadySpy.mockResolvedValue({ backfill: { status: 'idle' } } as ReadyStatus)

    const { Wrapper } = makeWrapper()
    renderHook(() => useReadyStatus(), { wrapper: Wrapper })

    await vi.waitFor(() => {
      expect(fetchReadySpy).toHaveBeenCalledTimes(1)
    })

    await vi.advanceTimersByTimeAsync(3000)
    await vi.waitFor(() => {
      expect(fetchReadySpy).toHaveBeenCalledTimes(2)
    })

    await vi.advanceTimersByTimeAsync(3000)
    await vi.waitFor(() => {
      expect(fetchReadySpy).toHaveBeenCalledTimes(3)
    })

    const callsBeforeWait = fetchReadySpy.mock.calls.length
    await vi.advanceTimersByTimeAsync(10000)
    expect(fetchReadySpy.mock.calls.length).toBe(callsBeforeWait)
  })

  it('treats 503 with running status as running (regression: did not throw)', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ backfill: { status: 'running' } }), {
        status: 503,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const { Wrapper } = makeWrapper()

    const { result } = renderHook(() => useReadyStatus(), { wrapper: Wrapper })

    await vi.waitFor(() => {
      expect(result.current.isRunning).toBe(true)
    })
  })
})
