import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ChatHttpError, parseRetryAfterSeconds, streamChat } from './chat'
import type { ChatStreamEvent, Source } from './chat'
import { buildErrorResponse, mockSseStream } from '@/test-utils/sseMocks'

async function collect(stream: AsyncIterable<ChatStreamEvent>): Promise<ChatStreamEvent[]> {
  const events: ChatStreamEvent[] = []
  for await (const event of stream) events.push(event)
  return events
}

function frame(name: string, data: unknown): string {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
}

function freshSignal(): AbortSignal {
  return new AbortController().signal
}

describe('parseRetryAfterSeconds', () => {
  const cases: Array<{ name: string; raw: string | null; expected: number }> = [
    { name: 'parses integer seconds', raw: '10', expected: 10 },
    { name: 'returns 10 for non-integer', raw: 'abc', expected: 10 },
    { name: 'returns 10 when missing', raw: null, expected: 10 },
    { name: 'returns 10 for zero', raw: '0', expected: 10 },
  ]

  for (const c of cases) {
    it(c.name, () => {
      const headers = new Headers(c.raw === null ? {} : { 'Retry-After': c.raw })
      expect(parseRetryAfterSeconds(headers)).toBe(c.expected)
    })
  }
})

describe('streamChat', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('emits queued event correctly parsed', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([frame('queued', { position: 2, estimatedWait: 30 })]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'queued', position: 2, estimatedWait: 30 }])
  })

  it.each([
    'Embedding query...',
    'Searching documents...',
    'Generating response...',
  ])('emits processing event for %s', async (status) => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([frame('processing', { status })]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'processing', status }])
  })

  it('emits multiple token events in order', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([
        frame('token', { text: 'Hello' }),
        frame('token', { text: ' ' }),
        frame('token', { text: 'world' }),
      ]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([
      { type: 'token', text: 'Hello' },
      { type: 'token', text: ' ' },
      { type: 'token', text: 'world' },
    ])
  })

  it('emits sources event with full Source[] shape including null section/page', async () => {
    const sources: Source[] = [
      {
        documentId: 1,
        documentName: 'Manual.docx',
        section: 'Section 3.2',
        page: 12,
        snippet: 'Some text',
      },
      {
        documentId: 2,
        documentName: 'Notes.txt',
        section: null,
        page: null,
        snippet: 'Other text',
      },
    ]

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([frame('sources', { sources })]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'sources', sources }])
  })

  it('emits done event terminating the stream', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([
        frame('token', { text: 'hi' }),
        frame('done', { totalTokens: 7 }),
      ]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([
      { type: 'token', text: 'hi' },
      { type: 'done', totalTokens: 7 },
    ])
  })

  it('emits error event terminating the stream', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([frame('error', { message: 'LLM timed out' })]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'error', message: 'LLM timed out' }])
  })

  it('handles a chunk that splits mid-frame', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream(['event: tok', 'en\ndata: {"text":"hi"}\n\n']),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'token', text: 'hi' }])
  })

  it('handles \\r\\n line endings', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream(['event: token\r\ndata: {"text":"hi"}\r\n\r\n']),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'token', text: 'hi' }])
  })

  it('drops unknown event names', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      mockSseStream([
        frame('mystery', { foo: 'bar' }),
        frame('token', { text: 'hi' }),
      ]),
    )

    const events = await collect(
      streamChat({ message: 'hi', history: [] }, freshSignal()),
    )

    expect(events).toEqual([{ type: 'token', text: 'hi' }])
  })

  it('throws ChatHttpError with parsed body on 400 invalid_request, message_too_long', async () => {
    const body = { error: 'invalid_request', reason: 'message_too_long' }
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(buildErrorResponse(400, body))

    let caught: unknown
    try {
      await collect(streamChat({ message: 'long', history: [] }, freshSignal()))
    } catch (error) {
      caught = error
    }

    expect(caught).toBeInstanceOf(ChatHttpError)
    const error = caught as ChatHttpError
    expect(error.status).toBe(400)
    expect(error.body).toEqual(body)
  })

  it('throws ChatHttpError on 503 with Retry-After: 7 — retryAfterSeconds === 7', async () => {
    const body = { error: 'not_ready', reason: 'embedding_backfill_in_progress' }
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      buildErrorResponse(503, body, { 'Retry-After': '7' }),
    )

    let caught: unknown
    try {
      await collect(streamChat({ message: 'hi', history: [] }, freshSignal()))
    } catch (error) {
      caught = error
    }

    expect(caught).toBeInstanceOf(ChatHttpError)
    const error = caught as ChatHttpError
    expect(error.status).toBe(503)
    expect(error.retryAfterSeconds).toBe(7)
    expect(error.body).toEqual(body)
  })

  it('throws ChatHttpError on 503 without Retry-After — retryAfterSeconds === 10', async () => {
    const body = { error: 'queue_unavailable' }
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(buildErrorResponse(503, body))

    let caught: unknown
    try {
      await collect(streamChat({ message: 'hi', history: [] }, freshSignal()))
    } catch (error) {
      caught = error
    }

    expect(caught).toBeInstanceOf(ChatHttpError)
    expect((caught as ChatHttpError).retryAfterSeconds).toBe(10)
  })

  it('aborts cleanly when AbortSignal fires mid-stream (no thrown error)', async () => {
    const controller = new AbortController()
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(streamController) {
        streamController.enqueue(encoder.encode(frame('token', { text: 'hi' })))
        controller.signal.addEventListener('abort', () => {
          try {
            streamController.error(
              Object.assign(new Error('aborted'), { name: 'AbortError' }),
            )
          } catch {
            // stream may already be closed/errored
          }
        })
      },
    })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(stream, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }),
    )

    const events: ChatStreamEvent[] = []
    for await (const event of streamChat(
      { message: 'hi', history: [] },
      controller.signal,
    )) {
      events.push(event)
      controller.abort()
    }

    expect(events).toEqual([{ type: 'token', text: 'hi' }])
  })

  it('stops yielding buffered events when signal aborts between yields in same chunk', async () => {
    const controller = new AbortController()
    // One chunk with TWO events. Without the in-loop abort check, the generator
    // would yield event B after the consumer called abort() on event A.
    const chunk = frame('token', { text: 'A' }) + frame('token', { text: 'B' })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockSseStream([chunk]))

    const events: ChatStreamEvent[] = []
    for await (const event of streamChat(
      { message: 'hi', history: [] },
      controller.signal,
    )) {
      events.push(event)
      if (events.length === 1) controller.abort()
    }

    expect(events).toEqual([{ type: 'token', text: 'A' }])
  })

  it('aborts cleanly when AbortSignal fires BEFORE fetch resolves (no AbortError propagated)', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.reject(Object.assign(new Error('aborted'), { name: 'AbortError' })),
    )

    const events: ChatStreamEvent[] = []
    for await (const event of streamChat(
      { message: 'hi', history: [] },
      controller.signal,
    )) {
      events.push(event)
    }

    expect(events).toEqual([])
  })
})
