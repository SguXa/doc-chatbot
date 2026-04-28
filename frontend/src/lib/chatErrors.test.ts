import { describe, it, expect } from 'vitest'
import { ChatHttpError } from '@/api/chat'
import {
  mapHttpError,
  mapMidStreamError,
  formatChatUxError,
  type ChatUxError,
} from './chatErrors'

describe('mapHttpError', () => {
  const cases: Array<{
    name: string
    error: unknown
    expected: ChatUxError
  }> = [
    {
      name: '503 embedding_backfill_in_progress with explicit Retry-After=5',
      error: new ChatHttpError(
        503,
        { error: 'not_ready', reason: 'embedding_backfill_in_progress' },
        5,
      ),
      expected: { kind: 'backfill_running', retryAfterSeconds: 5 },
    },
    {
      name: '503 embedding_backfill_in_progress with default Retry-After=10',
      error: new ChatHttpError(
        503,
        { error: 'not_ready', reason: 'embedding_backfill_in_progress' },
        10,
      ),
      expected: { kind: 'backfill_running', retryAfterSeconds: 10 },
    },
    {
      name: '503 embedding_backfill_failed with operator message',
      error: new ChatHttpError(
        503,
        { error: 'not_ready', reason: 'embedding_backfill_failed', message: 'corrupted db' },
        10,
      ),
      expected: { kind: 'backfill_failed', message: 'corrupted db' },
    },
    {
      name: '503 queue_unavailable',
      error: new ChatHttpError(503, { error: 'queue_unavailable' }, 10),
      expected: { kind: 'queue_unavailable' },
    },
    {
      name: '400 invalid_request / message_too_long',
      error: new ChatHttpError(
        400,
        { error: 'invalid_request', reason: 'message_too_long' },
        10,
      ),
      expected: { kind: 'invalid_request', reason: 'message_too_long' },
    },
    {
      name: 'TypeError from fetch (network failure)',
      error: new TypeError('Failed to fetch'),
      expected: { kind: 'network_failure' },
    },
    {
      name: 'generic Error fallback',
      error: new Error('boom'),
      expected: { kind: 'unknown', message: 'boom' },
    },
    {
      name: 'non-Error value fallback',
      error: 'mystery string',
      expected: { kind: 'unknown', message: 'Unknown error' },
    },
    {
      name: 'unknown 503 body still falls through to unknown with status',
      error: new ChatHttpError(503, { error: 'something_unexpected' }, 10),
      expected: { kind: 'unknown', status: 503, message: 'Chat API error: 503' },
    },
  ]

  for (const { name, error, expected } of cases) {
    it(name, () => {
      expect(mapHttpError(error)).toEqual(expected)
    })
  }
})

describe('mapMidStreamError', () => {
  it('wraps the message in a mid_stream ux error', () => {
    expect(mapMidStreamError('LLM timed out')).toEqual({
      kind: 'mid_stream',
      message: 'LLM timed out',
    })
  })
})

describe('formatChatUxError', () => {
  const cases: Array<{ name: string; ux: ChatUxError; expected: string }> = [
    {
      name: 'network_failure',
      ux: { kind: 'network_failure' },
      expected: 'Unable to reach server. Please check your connection.',
    },
    {
      name: 'queue_unavailable',
      ux: { kind: 'queue_unavailable' },
      expected: 'Server is temporarily unavailable. Please try again.',
    },
    {
      name: 'mid_stream',
      ux: { kind: 'mid_stream', message: 'LLM timed out' },
      expected: 'An error occurred: LLM timed out',
    },
    {
      name: 'backfill_failed',
      ux: { kind: 'backfill_failed', message: 'corrupted db' },
      expected: 'Knowledge base unavailable. Please contact your administrator.',
    },
    {
      name: 'invalid_request',
      ux: { kind: 'invalid_request', reason: 'message_too_long' },
      expected: 'Invalid request: message_too_long',
    },
    {
      name: 'unknown',
      ux: { kind: 'unknown', message: 'boom' },
      expected: 'Unexpected error: boom',
    },
  ]

  for (const { name, ux, expected } of cases) {
    it(name, () => {
      expect(formatChatUxError(ux)).toBe(expected)
    })
  }
})
