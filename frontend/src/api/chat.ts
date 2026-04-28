interface Source {
  documentId: number
  documentName: string
  section: string | null
  page: number | null
  snippet: string
}

interface ChatHistoryEntry {
  role: 'user' | 'assistant'
  content: string
}

interface ChatRequestBody {
  message: string
  history: ChatHistoryEntry[]
}

type ChatStreamEvent =
  | { type: 'queued'; position: number; estimatedWait: number }
  | { type: 'processing'; status: string }
  | { type: 'token'; text: string }
  | { type: 'sources'; sources: Source[] }
  | { type: 'done'; totalTokens: number }
  | { type: 'error'; message: string }

const RETRY_AFTER_DEFAULT_SECONDS = 10
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

class ChatHttpError extends Error {
  status: number
  body: unknown
  retryAfterSeconds: number

  constructor(status: number, body: unknown, retryAfterSeconds: number) {
    super(`Chat API error: ${status}`)
    this.name = 'ChatHttpError'
    this.status = status
    this.body = body
    this.retryAfterSeconds = retryAfterSeconds
  }

  static fromResponse(response: Response, body: unknown): ChatHttpError {
    return new ChatHttpError(
      response.status,
      body,
      parseRetryAfterSeconds(response.headers),
    )
  }
}

function parseRetryAfterSeconds(headers: Headers): number {
  const raw = headers.get('Retry-After')
  if (raw === null) return RETRY_AFTER_DEFAULT_SECONDS
  const parsed = parseInt(raw, 10)
  if (!Number.isFinite(parsed) || parsed <= 0) return RETRY_AFTER_DEFAULT_SECONDS
  return parsed
}

function isAbortError(error: unknown): boolean {
  return (
    error !== null &&
    typeof error === 'object' &&
    (error as { name?: unknown }).name === 'AbortError'
  )
}

async function parseJsonBody(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return undefined
  }
}

function parseSseEvent(name: string, data: string): ChatStreamEvent | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return null
  }
  if (parsed === null || typeof parsed !== 'object') return null
  const obj = parsed as Record<string, unknown>
  switch (name) {
    case 'queued':
      return {
        type: 'queued',
        position: Number(obj.position ?? 0),
        estimatedWait: Number(obj.estimatedWait ?? 0),
      }
    case 'processing':
      return { type: 'processing', status: String(obj.status ?? '') }
    case 'token':
      return { type: 'token', text: String(obj.text ?? '') }
    case 'sources':
      return {
        type: 'sources',
        sources: Array.isArray(obj.sources) ? (obj.sources as Source[]) : [],
      }
    case 'done':
      return { type: 'done', totalTokens: Number(obj.totalTokens ?? 0) }
    case 'error':
      return { type: 'error', message: String(obj.message ?? '') }
    default:
      return null
  }
}

async function* streamChat(
  body: ChatRequestBody,
  signal: AbortSignal,
): AsyncGenerator<ChatStreamEvent, void, void> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}/api/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(body),
      signal,
    })
  } catch (error) {
    if (isAbortError(error) || signal.aborted) return
    throw error
  }

  if (!response.ok) {
    const errorBody = await parseJsonBody(response)
    throw ChatHttpError.fromResponse(response, errorBody)
  }

  if (!response.body) return

  const reader = response.body.getReader()
  const decoder = new TextDecoder()

  let buffer = ''
  let eventName = ''
  const dataLines: string[] = []

  try {
    while (!signal.aborted) {
      let chunk: ReadableStreamReadResult<Uint8Array>
      try {
        chunk = await reader.read()
      } catch (error) {
        if (isAbortError(error) || signal.aborted) return
        throw error
      }
      if (chunk.done) break
      buffer += decoder.decode(chunk.value, { stream: true })

      for (;;) {
        // A chunk may contain several buffered events; without this check the
        // generator would keep yielding them after the consumer aborted between
        // yields. That would let stale tokens/sources from a cancelled run leak
        // into a retry that reuses the same assistant id.
        if (signal.aborted) return
        const nlIndex = buffer.indexOf('\n')
        if (nlIndex === -1) break
        let line = buffer.slice(0, nlIndex)
        buffer = buffer.slice(nlIndex + 1)
        if (line.endsWith('\r')) line = line.slice(0, -1)

        if (line === '') {
          if (eventName) {
            const event = parseSseEvent(eventName, dataLines.join('\n'))
            if (event) yield event
          }
          eventName = ''
          dataLines.length = 0
          continue
        }
        if (line.startsWith('event:')) {
          eventName = line.slice('event:'.length).trim()
        } else if (line.startsWith('data:')) {
          let payload = line.slice('data:'.length)
          if (payload.startsWith(' ')) payload = payload.slice(1)
          dataLines.push(payload)
        }
      }
    }
  } finally {
    try {
      reader.releaseLock()
    } catch {
      // releaseLock can throw if a read is still in flight; the iteration has
      // already exited, so swallowing here is safe.
    }
  }
}

export { streamChat, ChatHttpError, parseRetryAfterSeconds }
export type { Source, ChatHistoryEntry, ChatRequestBody, ChatStreamEvent }
