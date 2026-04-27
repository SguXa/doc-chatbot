import { ApiError, UnauthorizedError } from '@/api/client'

interface DocumentDto {
  id: number
  filename: string
  fileType: 'docx' | 'pdf'
  fileSize: number
  fileHash: string
  chunkCount: number
  imageCount: number
  indexedAt: string
  createdAt: string
}

type ParsedErrorKind =
  | 'duplicate'
  | 'unsupported_extension'
  | 'unreadable_document'
  | 'empty_content'
  | 'malformed_multipart'
  | 'file_too_large'
  | 'invalid_content_type'
  | 'ollama_unavailable'
  | 'reindex_in_progress'
  | 'invalid_credentials'
  | 'empty_password'
  | 'malformed_body'
  | 'empty_prompt'
  | 'prompt_too_long'
  | 'unauthorized'
  | 'unknown'

interface ParsedError {
  kind: ParsedErrorKind
  message: string
  existing?: DocumentDto
}

interface ErrorBody {
  error?: string
  reason?: string
  message?: string
  existing?: DocumentDto
}

function parseApiError(error: unknown): ParsedError {
  if (error instanceof UnauthorizedError) {
    return { kind: 'unauthorized', message: 'Session expired. Please log in again.' }
  }

  if (!(error instanceof ApiError)) {
    if (error instanceof Error) {
      return { kind: 'unknown', message: error.message || 'Server error' }
    }
    return { kind: 'unknown', message: 'Server error' }
  }

  const body = error.body as ErrorBody | undefined
  if (!body || typeof body !== 'object') {
    return { kind: 'unknown', message: error.message || 'Server error' }
  }

  const code = body.error
  const reason = body.reason

  switch (code) {
    case 'duplicate_document':
      return {
        kind: 'duplicate',
        message: 'A document with identical content has already been indexed.',
        existing: body.existing,
      }
    case 'invalid_upload':
      switch (reason) {
        case 'unsupported_extension':
          return { kind: 'unsupported_extension', message: 'Only .docx and .pdf are supported' }
        case 'malformed_multipart':
          return { kind: 'malformed_multipart', message: 'File is corrupted, please retry' }
        case 'file_too_large':
          return { kind: 'file_too_large', message: 'File exceeds 100 MB limit' }
        case 'invalid_content_type':
          return { kind: 'invalid_content_type', message: 'Invalid request format' }
        default:
          return { kind: 'unknown', message: body.message ?? error.message ?? 'Server error' }
      }
    case 'unreadable_document':
      return {
        kind: 'unreadable_document',
        message: `Could not read file: ${body.message ?? 'document is unreadable'}`,
      }
    case 'empty_content':
      return { kind: 'empty_content', message: 'No extractable text in file' }
    case 'ollama_unavailable':
      return {
        kind: 'ollama_unavailable',
        message: 'Ollama is unavailable. Upload was rolled back, retry shortly.',
      }
    case 'reindex_in_progress':
      return { kind: 'reindex_in_progress', message: 'Reindex is running, retry in a minute.' }
    case 'invalid_credentials':
      return { kind: 'invalid_credentials', message: 'Invalid password' }
    case 'invalid_request':
      switch (reason) {
        case 'empty_password':
          return { kind: 'empty_password', message: 'Enter password' }
        case 'malformed_body':
          return { kind: 'malformed_body', message: 'Malformed request body' }
        case 'empty_prompt':
          return { kind: 'empty_prompt', message: 'System prompt cannot be empty' }
        case 'prompt_too_long':
          return { kind: 'prompt_too_long', message: 'System prompt exceeds 8000 characters' }
        default:
          return { kind: 'unknown', message: body.message ?? error.message ?? 'Server error' }
      }
    default:
      return { kind: 'unknown', message: body.message ?? error.message ?? 'Server error' }
  }
}

export { parseApiError }
export type { ParsedError, ParsedErrorKind, DocumentDto }
