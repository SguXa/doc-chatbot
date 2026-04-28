import { ChatHttpError } from '@/api/chat'

type ChatUxError =
  | { kind: 'backfill_running'; retryAfterSeconds: number }
  | { kind: 'backfill_failed'; message: string }
  | { kind: 'queue_unavailable' }
  | { kind: 'network_failure' }
  | { kind: 'mid_stream'; message: string }
  | { kind: 'invalid_request'; reason: string }
  | { kind: 'unknown'; status?: number; message: string }

interface ChatErrorBody {
  error?: string
  reason?: string
  message?: string
}

function readBody(error: ChatHttpError): ChatErrorBody {
  const body = error.body
  if (body === null || typeof body !== 'object') return {}
  return body as ChatErrorBody
}

function mapHttpError(error: unknown): ChatUxError {
  if (error instanceof ChatHttpError) {
    const body = readBody(error)
    const code = body.error
    const reason = body.reason

    if (code === 'not_ready') {
      if (reason === 'embedding_backfill_in_progress') {
        return { kind: 'backfill_running', retryAfterSeconds: error.retryAfterSeconds }
      }
      if (reason === 'embedding_backfill_failed') {
        return { kind: 'backfill_failed', message: body.message ?? '' }
      }
    }
    if (code === 'queue_unavailable') {
      return { kind: 'queue_unavailable' }
    }
    if (code === 'invalid_request') {
      return { kind: 'invalid_request', reason: reason ?? '' }
    }
    return {
      kind: 'unknown',
      status: error.status,
      message: body.message ?? error.message,
    }
  }
  if (error instanceof TypeError) {
    return { kind: 'network_failure' }
  }
  if (error instanceof Error) {
    return { kind: 'unknown', message: error.message || 'Unknown error' }
  }
  return { kind: 'unknown', message: 'Unknown error' }
}

function mapMidStreamError(message: string): ChatUxError {
  return { kind: 'mid_stream', message }
}

// `backfill_running` is intercepted by the orchestrator (auto-retry) and never
// stored on a message, so it is excluded from the renderable subset.
type RenderableChatUxError = Exclude<ChatUxError, { kind: 'backfill_running' }>

function formatChatUxError(uxError: RenderableChatUxError): string {
  switch (uxError.kind) {
    case 'backfill_failed':
      return 'Knowledge base unavailable. Please contact your administrator.'
    case 'queue_unavailable':
      return 'Server is temporarily unavailable. Please try again.'
    case 'network_failure':
      return 'Unable to reach server. Please check your connection.'
    case 'mid_stream':
      return `An error occurred: ${uxError.message}`
    case 'invalid_request':
      return `Invalid request: ${uxError.reason}`
    case 'unknown':
      return `Unexpected error: ${uxError.message}`
  }
}

export { mapHttpError, mapMidStreamError, formatChatUxError }
export type { ChatUxError, RenderableChatUxError }
