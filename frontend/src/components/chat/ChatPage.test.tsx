import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ChatPage } from './ChatPage'
import * as useReadyStatusModule from '@/hooks/useReadyStatus'
import type { BackfillStatus } from '@/api/admin'
import { useChatStore } from '@/stores/chatStore'
import { mockSseStream, buildErrorResponse } from '@/test-utils/sseMocks'

function mockReadyStatus(status: BackfillStatus = 'ready') {
  vi.spyOn(useReadyStatusModule, 'useReadyStatus').mockReturnValue({
    status,
    isRunning: status === 'running',
  })
}

function renderChatPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ChatPage />
      </QueryClientProvider>,
    ),
  }
}

function frame(name: string, data: unknown): string {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
}

function typeAndSend(text: string) {
  const textarea = screen.getByPlaceholderText(/ask a question/i)
  fireEvent.change(textarea, { target: { value: text } })
  fireEvent.keyDown(textarea, { key: 'Enter' })
}

async function flushPromises() {
  // Two ticks: one to settle fetch, one to start consuming the stream.
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
}

interface FetchCall {
  url: string
  init: RequestInit
}

function readFetchCall(call: unknown[]): FetchCall {
  return { url: String(call[0]), init: call[1] as RequestInit }
}

function parseFetchBody(call: unknown[]): { message: string; history: Array<{ role: string; content: string }> } {
  return JSON.parse(String(readFetchCall(call).init.body))
}

describe('ChatPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    useChatStore.setState({ messages: [], isStreaming: false })
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('layout/blocker (existing coverage)', () => {
    it('renders without crashing', () => {
      mockReadyStatus('idle')
      const { container } = renderChatPage()
      expect(container).toBeTruthy()
    })

    it('has both <main> and <aside> landmarks', () => {
      mockReadyStatus('idle')
      const { container } = renderChatPage()
      expect(container.querySelector('aside')).not.toBeNull()
      expect(container.querySelector('main')).not.toBeNull()
    })

    it('chat content wrapper has max-w-3xl', () => {
      mockReadyStatus('idle')
      const { container } = renderChatPage()
      const main = container.querySelector('main')
      expect(main!.querySelector('.max-w-3xl')).not.toBeNull()
    })

    it('shows the backfill banner and chat surface when status is running', () => {
      mockReadyStatus('running')
      renderChatPage()
      expect(
        screen.getByText(/knowledge base is being prepared/i),
      ).toBeInTheDocument()
      expect(screen.getByPlaceholderText(/ask a question/i)).toBeInTheDocument()
    })

    it('renders the blocker card when status is failed; hides MessageList and ChatInput', () => {
      mockReadyStatus('failed')
      renderChatPage()
      expect(
        screen.getByText(/knowledge base unavailable/i),
      ).toBeInTheDocument()
      expect(screen.queryByPlaceholderText(/ask a question/i)).not.toBeInTheDocument()
      expect(
        screen.queryByText(/ask a question about aos documentation/i),
      ).not.toBeInTheDocument()
    })

    it('still renders the sidebar in the blocker state', () => {
      mockReadyStatus('failed')
      const { container } = renderChatPage()
      expect(container.querySelector('aside')).not.toBeNull()
    })
  })

  describe('orchestration', () => {
    beforeEach(() => {
      mockReadyStatus('ready')
    })

    it('happy path: sends with the right body, folds events into the store', async () => {
      const sources = [
        {
          documentId: 1,
          documentName: 'Manual.docx',
          section: 'Section 1',
          page: 1,
          snippet: 'snip',
        },
      ]
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        mockSseStream([
          frame('queued', { position: 1, estimatedWait: 5 }),
          frame('processing', { status: 'Generating response...' }),
          frame('token', { text: 'Hello' }),
          frame('token', { text: ' world' }),
          frame('sources', { sources }),
          frame('done', { totalTokens: 2 }),
        ]),
      )

      renderChatPage()
      typeAndSend('My question')

      await waitFor(() => {
        const messages = useChatStore.getState().messages
        const last = messages[messages.length - 1]
        expect(last?.status).toBe('done')
      })

      const messages = useChatStore.getState().messages
      expect(messages).toHaveLength(2)
      expect(messages[0]).toMatchObject({ role: 'user', content: 'My question', status: 'done' })
      expect(messages[1]).toMatchObject({
        role: 'assistant',
        content: 'Hello world',
        status: 'done',
      })
      expect(messages[1].sources).toEqual(sources)

      const calls = vi.mocked(globalThis.fetch).mock.calls
      const body = parseFetchBody(calls[0])
      expect(body).toEqual({ message: 'My question', history: [] })
      expect(useChatStore.getState().isStreaming).toBe(false)
    })

    it('history filter: drops error-state messages when building history', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        mockSseStream([frame('done', { totalTokens: 0 })]),
      )

      useChatStore.setState({
        messages: [
          { id: 'u1', role: 'user', content: 'Q1', status: 'done' },
          { id: 'a1', role: 'assistant', content: 'A1', status: 'done' },
          {
            id: 'a2',
            role: 'assistant',
            content: 'partial',
            status: 'error',
            uxError: { kind: 'queue_unavailable' },
          },
        ],
        isStreaming: false,
      })

      renderChatPage()
      typeAndSend('Q2')

      await waitFor(() => {
        expect(globalThis.fetch).toHaveBeenCalled()
      })

      const calls = vi.mocked(globalThis.fetch).mock.calls
      const body = parseFetchBody(calls[0])
      expect(body.history).toHaveLength(2)
      expect(body.history).toEqual([
        { role: 'user', content: 'Q1' },
        { role: 'assistant', content: 'A1' },
      ])
    })

    it('history slice: keeps only the most recent 20 entries', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        mockSseStream([frame('done', { totalTokens: 0 })]),
      )

      // 11 user/assistant pairs = 22 done entries, all properly paired so
      // buildHistory keeps every one of them before slicing.
      const seeded = Array.from({ length: 22 }, (_, i) => ({
        id: `m${i}`,
        role: (i % 2 === 0 ? 'user' : 'assistant') as 'user' | 'assistant',
        content: `msg-${i}`,
        status: 'done' as const,
      }))
      useChatStore.setState({ messages: seeded, isStreaming: false })

      renderChatPage()
      typeAndSend('next')

      await waitFor(() => {
        expect(globalThis.fetch).toHaveBeenCalled()
      })

      const body = parseFetchBody(vi.mocked(globalThis.fetch).mock.calls[0])
      expect(body.history).toHaveLength(20)
      // Oldest two (msg-0, msg-1) dropped; msg-2 is now first.
      expect(body.history[0]).toEqual({ role: 'user', content: 'msg-2' })
      expect(body.history[19]).toEqual({ role: 'assistant', content: 'msg-21' })
    })

    it('history filter: drops user messages whose paired assistant did not complete', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        mockSseStream([frame('done', { totalTokens: 0 })]),
      )

      useChatStore.setState({
        messages: [
          { id: 'u1', role: 'user', content: 'Q1', status: 'done' },
          { id: 'a1', role: 'assistant', content: 'A1', status: 'done' },
          { id: 'u2', role: 'user', content: 'Q2-failed', status: 'done' },
          {
            id: 'a2',
            role: 'assistant',
            content: '',
            status: 'error',
            uxError: { kind: 'queue_unavailable' },
          },
        ],
        isStreaming: false,
      })

      renderChatPage()
      typeAndSend('Q3')

      await waitFor(() => {
        expect(globalThis.fetch).toHaveBeenCalled()
      })

      const body = parseFetchBody(vi.mocked(globalThis.fetch).mock.calls[0])
      // u2 is dropped because its paired a2 is in error; only the completed
      // u1/a1 pair survives.
      expect(body.history).toEqual([
        { role: 'user', content: 'Q1' },
        { role: 'assistant', content: 'A1' },
      ])
    })

    it('stream ends without terminal event → assistant gets an error state with Retry', async () => {
      // Server returned 200 OK with a body that closes mid-stream — no `done`
      // and no `error` event arrives. Without the explicit guard the row would
      // be stuck in queued/processing/streaming with no Retry button.
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(
          mockSseStream([
            frame('processing', { status: 'Generating response...' }),
            frame('token', { text: 'partial' }),
          ]),
        )
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'recovered' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
      })

      const errored = useChatStore.getState().messages.at(-1)!
      expect(errored.uxError).toEqual({
        kind: 'mid_stream',
        message: 'Connection closed unexpectedly',
      })
      expect(useChatStore.getState().isStreaming).toBe(false)

      fireEvent.click(screen.getByRole('button', { name: /retry/i }))

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('done')
        expect(last.content).toBe('recovered')
      })
    })

    it('200 OK with null body → assistant gets an error state', async () => {
      // Some proxies / mocks return 200 with no body. streamChat returns
      // immediately; the orchestration must still surface an error rather
      // than silently leaving the row in queued state.
      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
        new Response(null, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }),
      )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
      })

      const errored = useChatStore.getState().messages.at(-1)!
      expect(errored.uxError).toEqual({
        kind: 'mid_stream',
        message: 'Connection closed unexpectedly',
      })
    })

    it('mid-stream error → message ends in error status; clicking Retry reruns successfully', async () => {
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(
          mockSseStream([frame('error', { message: 'LLM timed out' })]),
        )
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'recovered' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
      })

      expect(screen.getByText(/an error occurred: llm timed out/i)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /retry/i }))

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('done')
        expect(last.content).toBe('recovered')
      })

      expect(globalThis.fetch).toHaveBeenCalledTimes(2)
    })

    it('retry on older failed turn caps history at the retried turn', async () => {
      // Transcript: Q1/A1 done, Q2/A2 errored, Q3/A3 done. Retrying A2
      // must not include Q3/A3 as "prior" context — that would feed the
      // LLM later turns as if they preceded Q2.
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        mockSseStream([
          frame('token', { text: 'recovered' }),
          frame('done', { totalTokens: 1 }),
        ]),
      )

      useChatStore.setState({
        messages: [
          { id: 'u1', role: 'user', content: 'Q1', status: 'done' },
          { id: 'a1', role: 'assistant', content: 'A1', status: 'done' },
          { id: 'u2', role: 'user', content: 'Q2', status: 'done' },
          {
            id: 'a2',
            role: 'assistant',
            content: '',
            status: 'error',
            uxError: { kind: 'queue_unavailable' },
          },
          { id: 'u3', role: 'user', content: 'Q3', status: 'done' },
          { id: 'a3', role: 'assistant', content: 'A3', status: 'done' },
        ],
        isStreaming: false,
      })

      renderChatPage()

      // Multiple Retry buttons could exist; the failed one is on a2.
      const retryButtons = screen.getAllByRole('button', { name: /retry/i })
      fireEvent.click(retryButtons[0])

      await waitFor(() => {
        expect(globalThis.fetch).toHaveBeenCalled()
      })

      const body = parseFetchBody(vi.mocked(globalThis.fetch).mock.calls[0])
      expect(body.message).toBe('Q2')
      // Only Q1/A1 (turns before the retried one) should be in history;
      // Q3/A3 must not appear.
      expect(body.history).toEqual([
        { role: 'user', content: 'Q1' },
        { role: 'assistant', content: 'A1' },
      ])
    })

    it('pre-SSE 503 backfill_running → countdown shown → second attempt succeeds', async () => {
      vi.useFakeTimers()
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(
          buildErrorResponse(
            503,
            { error: 'not_ready', reason: 'embedding_backfill_in_progress' },
            { 'Retry-After': '1' },
          ),
        )
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'after-retry' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('hi')

      // Let the first fetch resolve and the catch path schedule the retry.
      await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
        await Promise.resolve()
      })

      const assistant = useChatStore.getState().messages.find((m) => m.role === 'assistant')!
      expect(assistant.statusText).toBe('Retrying in 1s…')

      // Advance past the 1-second countdown — this fires the auto-retry
      // setTimeout, which kicks off runStream a second time. advanceTimersByTimeAsync
      // also flushes pending microtasks so the second fetch settles and the
      // stream is consumed end-to-end inside the same tick window.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })

      const last = useChatStore.getState().messages.at(-1)!
      expect(last.status).toBe('done')
      expect(last.content).toBe('after-retry')

      expect(globalThis.fetch).toHaveBeenCalledTimes(2)
    })

    it('pre-SSE 503 queue_unavailable → error state; manual Retry recovers', async () => {
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(buildErrorResponse(503, { error: 'queue_unavailable' }))
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'ok' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
      })

      expect(screen.getByText(/server is temporarily unavailable/i)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /retry/i }))

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('done')
        expect(last.content).toBe('ok')
      })
    })

    it('pre-SSE 503 backfill_failed → error state with the formatted text', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        buildErrorResponse(503, {
          error: 'not_ready',
          reason: 'embedding_backfill_failed',
          message: 'corrupted db',
        }),
      )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
        expect(last.uxError).toEqual({ kind: 'backfill_failed', message: 'corrupted db' })
      })

      expect(
        screen.getByText(/knowledge base unavailable\. please contact your administrator\./i),
      ).toBeInTheDocument()
    })

    it('pre-SSE 400 invalid_request → error state with reason in message', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        buildErrorResponse(400, {
          error: 'invalid_request',
          reason: 'message_too_long',
        }),
      )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
        expect(last.uxError).toEqual({ kind: 'invalid_request', reason: 'message_too_long' })
      })

      expect(screen.getByText(/invalid request: message_too_long/i)).toBeInTheDocument()
    })

    it('network failure (TypeError) → error state; manual Retry recovers', async () => {
      vi.spyOn(globalThis, 'fetch')
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'ok' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('error')
        expect(last.uxError).toEqual({ kind: 'network_failure' })
      })

      expect(screen.getByText(/unable to reach server/i)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /retry/i }))

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('done')
      })
    })

    it('abort-on-clear: mid-stream clearAll halts fetches and the next send streams cleanly', async () => {
      const encoder = new TextEncoder()
      const firstStream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode(frame('token', { text: 'partial' })))
          // Stream stays open — never closed by the server side. Abort triggers
          // a stream error which streamChat swallows.
        },
      })
      const firstResponse = new Response(firstStream, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })

      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(firstResponse)
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'second' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('first')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.content).toBe('partial')
      })
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)

      act(() => {
        useChatStore.getState().clearAll()
      })

      await flushPromises()

      // No further fetch as a side-effect of clearing.
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)
      // Messages are wiped. No phantom error message.
      expect(useChatStore.getState().messages).toEqual([])
      expect(useChatStore.getState().isStreaming).toBe(false)

      typeAndSend('second')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.status).toBe('done')
        expect(last.content).toBe('second')
      })
      expect(globalThis.fetch).toHaveBeenCalledTimes(2)
    })

    it('abort-on-unmount: unmount halts the in-flight stream without leaving a phantom error', async () => {
      const encoder = new TextEncoder()
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode(frame('token', { text: 'partial' })))
        },
      })
      const response = new Response(stream, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)

      const { unmount } = renderChatPage()
      typeAndSend('first')

      await waitFor(() => {
        const last = useChatStore.getState().messages.at(-1)!
        expect(last.content).toBe('partial')
      })

      const before = useChatStore.getState().messages
      unmount()
      await flushPromises()

      // Messages are unchanged: no setError, no setStatus after unmount.
      const after = useChatStore.getState().messages
      expect(after).toHaveLength(before.length)
      const last = after.at(-1)!
      expect(last.status).not.toBe('error')
    })

    it('clear-during-countdown: clearAll halts the pending auto-retry', async () => {
      vi.useFakeTimers()
      vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
        buildErrorResponse(
          503,
          { error: 'not_ready', reason: 'embedding_backfill_in_progress' },
          { 'Retry-After': '2' },
        ),
      )

      renderChatPage()
      typeAndSend('hi')

      await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
        await Promise.resolve()
      })

      const assistantId = useChatStore.getState().messages.find((m) => m.role === 'assistant')!.id
      expect(useChatStore.getState().messages.find((m) => m.id === assistantId)!.statusText).toBe(
        'Retrying in 2s…',
      )

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000)
      })

      expect(useChatStore.getState().messages.find((m) => m.id === assistantId)!.statusText).toBe(
        'Retrying in 1s…',
      )

      act(() => {
        useChatStore.getState().clearAll()
      })

      // No further fetch should ever fire after clear.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000)
      })

      expect(globalThis.fetch).toHaveBeenCalledTimes(1)
      expect(useChatStore.getState().messages).toEqual([])
    })

    it('countdown observable values: ticks 2s→1s→fires the second fetch', async () => {
      vi.useFakeTimers()
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(
          buildErrorResponse(
            503,
            { error: 'not_ready', reason: 'embedding_backfill_in_progress' },
            { 'Retry-After': '2' },
          ),
        )
        .mockResolvedValueOnce(
          mockSseStream([frame('done', { totalTokens: 0 })]),
        )

      renderChatPage()
      typeAndSend('hi')

      await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
        await Promise.resolve()
      })

      const assistantId = useChatStore.getState().messages.find((m) => m.role === 'assistant')!.id
      expect(useChatStore.getState().messages.find((m) => m.id === assistantId)!.statusText).toBe(
        'Retrying in 2s…',
      )

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000)
      })
      expect(useChatStore.getState().messages.find((m) => m.id === assistantId)!.statusText).toBe(
        'Retrying in 1s…',
      )

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000)
      })

      expect(globalThis.fetch).toHaveBeenCalledTimes(2)
    })

    it('retry-during-countdown: clicking Retry cancels the pending auto-retry and fires immediately', async () => {
      vi.useFakeTimers()
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(
          buildErrorResponse(
            503,
            { error: 'not_ready', reason: 'embedding_backfill_in_progress' },
            { 'Retry-After': '2' },
          ),
        )
        .mockResolvedValueOnce(
          mockSseStream([
            frame('token', { text: 'retry-ok' }),
            frame('done', { totalTokens: 1 }),
          ]),
        )

      renderChatPage()
      typeAndSend('hi')

      await act(async () => {
        await Promise.resolve()
        await Promise.resolve()
        await Promise.resolve()
      })

      // Synthetically expose the Retry button by flipping the assistant
      // message to error state. The orchestration's handleRetry path is what
      // we're verifying — that it cancels the pending auto-retry timer.
      const assistantId = useChatStore.getState().messages.find((m) => m.role === 'assistant')!.id
      act(() => {
        useChatStore.getState().setError(assistantId, { kind: 'queue_unavailable' })
      })

      // Click the now-visible Retry button.
      fireEvent.click(screen.getByRole('button', { name: /retry/i }))

      // Click → handleRetry kicks off runStream; advance microtasks via
      // advanceTimersByTimeAsync(0) so the fetch+stream settle.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(globalThis.fetch).toHaveBeenCalledTimes(2)

      // Advancing past the original countdown produces no third fetch — the
      // setTimeout was canceled by handleRetry.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000)
      })
      expect(globalThis.fetch).toHaveBeenCalledTimes(2)

      const last = useChatStore.getState().messages.at(-1)!
      expect(last.status).toBe('done')
      expect(last.content).toBe('retry-ok')
    })

    it('disabled on input is true while streaming and false after done', async () => {
      const encoder = new TextEncoder()
      let streamController: ReadableStreamDefaultController<Uint8Array> | null = null
      const stream = new ReadableStream<Uint8Array>({
        start(c) {
          streamController = c
          c.enqueue(encoder.encode(frame('processing', { status: 'Generating response...' })))
        },
      })
      const response = new Response(stream, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)

      renderChatPage()
      typeAndSend('hi')

      await waitFor(() => {
        expect(useChatStore.getState().isStreaming).toBe(true)
      })

      const sendButton = screen.getByRole('button', { name: /send/i })
      expect(sendButton).toBeDisabled()

      // Close the stream with a done event.
      await act(async () => {
        streamController!.enqueue(encoder.encode(frame('done', { totalTokens: 0 })))
        streamController!.close()
      })

      await waitFor(() => {
        expect(useChatStore.getState().isStreaming).toBe(false)
      })
    })
  })
})
