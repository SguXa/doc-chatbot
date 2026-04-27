import { describe, it, expect } from 'vitest'
import { ApiError, UnauthorizedError } from '@/api/client'
import { parseApiError, type DocumentDto, type ParsedErrorKind } from './errors'

function makeApiError(status: number, body: unknown): ApiError {
  return new ApiError(status, 'Error', body)
}

describe('parseApiError', () => {
  describe('discriminator mapping', () => {
    const cases: Array<{
      name: string
      status: number
      body: unknown
      expectedKind: ParsedErrorKind
      expectedMessage: string
    }> = [
      {
        name: 'invalid_upload / unsupported_extension',
        status: 400,
        body: { error: 'invalid_upload', reason: 'unsupported_extension', message: '...' },
        expectedKind: 'unsupported_extension',
        expectedMessage: 'Only .docx and .pdf are supported',
      },
      {
        name: 'invalid_upload / malformed_multipart',
        status: 400,
        body: { error: 'invalid_upload', reason: 'malformed_multipart', message: '...' },
        expectedKind: 'malformed_multipart',
        expectedMessage: 'File is corrupted, please retry',
      },
      {
        name: 'invalid_upload / file_too_large',
        status: 413,
        body: { error: 'invalid_upload', reason: 'file_too_large', message: '...' },
        expectedKind: 'file_too_large',
        expectedMessage: 'File exceeds 100 MB limit',
      },
      {
        name: 'invalid_upload / invalid_content_type',
        status: 415,
        body: { error: 'invalid_upload', reason: 'invalid_content_type', message: '...' },
        expectedKind: 'invalid_content_type',
        expectedMessage: 'Invalid request format',
      },
      {
        name: 'unreadable_document',
        status: 400,
        body: { error: 'unreadable_document', reason: 'corrupted_docx', message: 'parser bailed' },
        expectedKind: 'unreadable_document',
        expectedMessage: 'Could not read file: parser bailed',
      },
      {
        name: 'empty_content',
        status: 400,
        body: { error: 'empty_content', reason: 'no_extractable_content', message: '...' },
        expectedKind: 'empty_content',
        expectedMessage: 'No extractable text in file',
      },
      {
        name: 'ollama_unavailable',
        status: 503,
        body: { error: 'ollama_unavailable', message: '...' },
        expectedKind: 'ollama_unavailable',
        expectedMessage: 'Ollama is unavailable. Upload was rolled back, retry shortly.',
      },
      {
        name: 'reindex_in_progress',
        status: 503,
        body: { error: 'reindex_in_progress', message: '...' },
        expectedKind: 'reindex_in_progress',
        expectedMessage: 'Reindex is running, retry in a minute.',
      },
      {
        name: 'invalid_credentials',
        status: 401,
        body: { error: 'invalid_credentials' },
        expectedKind: 'invalid_credentials',
        expectedMessage: 'Invalid password',
      },
      {
        name: 'invalid_request / empty_password',
        status: 400,
        body: { error: 'invalid_request', reason: 'empty_password' },
        expectedKind: 'empty_password',
        expectedMessage: 'Enter password',
      },
      {
        name: 'invalid_request / malformed_body',
        status: 400,
        body: { error: 'invalid_request', reason: 'malformed_body' },
        expectedKind: 'malformed_body',
        expectedMessage: 'Malformed request body',
      },
      {
        name: 'invalid_request / empty_prompt',
        status: 400,
        body: { error: 'invalid_request', reason: 'empty_prompt' },
        expectedKind: 'empty_prompt',
        expectedMessage: 'System prompt cannot be empty',
      },
      {
        name: 'invalid_request / prompt_too_long',
        status: 400,
        body: { error: 'invalid_request', reason: 'prompt_too_long' },
        expectedKind: 'prompt_too_long',
        expectedMessage: 'System prompt exceeds 8000 characters',
      },
    ]

    for (const c of cases) {
      it(c.name, () => {
        const result = parseApiError(makeApiError(c.status, c.body))
        expect(result.kind).toBe(c.expectedKind)
        expect(result.message).toBe(c.expectedMessage)
      })
    }
  })

  describe('duplicate_document', () => {
    it('extracts the existing document payload', () => {
      const existing: DocumentDto = {
        id: 42,
        filename: 'troubleshooting_v2.docx',
        fileType: 'docx',
        fileSize: 12345,
        fileHash: 'abc123',
        chunkCount: 10,
        imageCount: 2,
        indexedAt: '2026-03-28T14:12:03Z',
        createdAt: '2026-03-28T14:12:03Z',
      }
      const error = makeApiError(409, {
        error: 'duplicate_document',
        message: 'A document with identical content has already been indexed.',
        existing,
      })

      const result = parseApiError(error)

      expect(result.kind).toBe('duplicate')
      expect(result.existing).toEqual(existing)
    })
  })

  describe('UnauthorizedError', () => {
    it('maps to kind=unauthorized', () => {
      const error = new UnauthorizedError('Unauthorized')
      const result = parseApiError(error)
      expect(result.kind).toBe('unauthorized')
      expect(result.message).toMatch(/log in|session/i)
    })
  })

  describe('fallbacks', () => {
    it('ApiError with body=undefined maps to unknown and preserves error.message', () => {
      const error = new ApiError(500, 'Internal Server Error', undefined)
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe(error.message)
    })

    it('ApiError with non-object body maps to unknown', () => {
      const error = new ApiError(500, 'Internal Server Error', 'plain text')
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe(error.message)
    })

    it('ApiError with unknown error discriminator falls through to unknown', () => {
      const error = makeApiError(500, { error: 'something_brand_new', message: 'boom' })
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe('boom')
    })

    it('invalid_upload with unknown reason falls through to unknown', () => {
      const error = makeApiError(400, {
        error: 'invalid_upload',
        reason: 'something_else',
        message: 'fallback message',
      })
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe('fallback message')
    })

    it('invalid_request with unknown reason falls through to unknown', () => {
      const error = makeApiError(400, {
        error: 'invalid_request',
        reason: 'something_else',
        message: 'fallback message',
      })
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe('fallback message')
    })

    it('plain Error maps to unknown with error.message', () => {
      const result = parseApiError(new Error('network down'))
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe('network down')
    })

    it('string thrown value maps to unknown with sensible default', () => {
      const result = parseApiError('something bad')
      expect(result.kind).toBe('unknown')
      expect(result.message).toBe('Server error')
    })

    it('null/undefined map to unknown with sensible default', () => {
      expect(parseApiError(null).kind).toBe('unknown')
      expect(parseApiError(undefined).kind).toBe('unknown')
    })
  })

  describe('DELETE-specific errors fall through (per Design Decisions)', () => {
    it('400 "Invalid document ID" → unknown', () => {
      const error = makeApiError(400, { error: 'Invalid document ID' })
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
    })

    it('404 "Document not found" → unknown', () => {
      const error = makeApiError(404, { error: 'Document not found' })
      const result = parseApiError(error)
      expect(result.kind).toBe('unknown')
    })
  })
})
