// Helpers for testing the SSE chat client (frontend/src/api/chat.ts).
//
// Sequential responses pattern: tests that need fetch to resolve to different
// responses across calls (e.g. first call → 503 backfill, second call → 200
// stream) chain mockImplementationOnce per call. Example:
//
//   vi.mocked(globalThis.fetch as typeof fetch)
//     .mockResolvedValueOnce(buildErrorResponse(503, body, { 'Retry-After': '1' }))
//     .mockResolvedValueOnce(mockSseStream([frame('done', { totalTokens: 5 })]))

interface MockSseOptions {
  status?: number
  headers?: Record<string, string>
}

function mockSseStream(chunks: string[], options: MockSseOptions = {}): Response {
  const { status = 200, headers = {} } = options
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
  const responseHeaders = new Headers({
    'Content-Type': 'text/event-stream',
    ...headers,
  })
  return new Response(stream, { status, headers: responseHeaders })
}

function buildErrorResponse(
  status: number,
  body: unknown,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...headers,
    },
  })
}

export { mockSseStream, buildErrorResponse }
