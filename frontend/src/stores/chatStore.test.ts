import { describe, it, expect, beforeEach } from 'vitest'
import { useChatStore, STORAGE_KEY, type Message } from './chatStore'
import type { ChatUxError } from '@/lib/chatErrors'

async function flushPersist() {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('chatStore', () => {
  beforeEach(async () => {
    useChatStore.setState({ messages: [], isStreaming: false })
    await flushPersist()
    sessionStorage.clear()
  })

  describe('addUserMessage', () => {
    it('appends a user message in done status and returns its id', () => {
      const id = useChatStore.getState().addUserMessage('hello')

      const messages = useChatStore.getState().messages
      expect(messages).toHaveLength(1)
      expect(messages[0]).toMatchObject({
        id,
        role: 'user',
        content: 'hello',
        status: 'done',
      })
    })

    it('produces unique ids for sequential calls', () => {
      const a = useChatStore.getState().addUserMessage('one')
      const b = useChatStore.getState().addUserMessage('two')
      expect(a).not.toBe(b)
    })
  })

  describe('addAssistantMessage', () => {
    it('appends an empty assistant message in queued status and returns its id', () => {
      const id = useChatStore.getState().addAssistantMessage()

      const messages = useChatStore.getState().messages
      expect(messages).toHaveLength(1)
      expect(messages[0]).toMatchObject({
        id,
        role: 'assistant',
        content: '',
        status: 'queued',
      })
    })

    it('produces ids distinct from user messages', () => {
      const userId = useChatStore.getState().addUserMessage('hi')
      const assistantId = useChatStore.getState().addAssistantMessage()
      expect(userId).not.toBe(assistantId)
    })
  })

  describe('setStatus', () => {
    it('updates only the named message', () => {
      const a = useChatStore.getState().addAssistantMessage()
      const b = useChatStore.getState().addAssistantMessage()

      useChatStore.getState().setStatus(a, 'processing', 'Working')

      const [first, second] = useChatStore.getState().messages
      expect(first).toMatchObject({ id: a, status: 'processing', statusText: 'Working' })
      expect(second).toMatchObject({ id: b, status: 'queued' })
      expect(second.statusText).toBeUndefined()
    })

    it('is a no-op for unknown id', () => {
      const id = useChatStore.getState().addAssistantMessage()
      const before = useChatStore.getState().messages

      useChatStore.getState().setStatus('does-not-exist', 'processing')

      const after = useChatStore.getState().messages
      expect(after[0]).toMatchObject({ id, status: 'queued' })
      expect(after[0]).toBe(before[0])
    })
  })

  describe('appendToken', () => {
    it('concatenates text and flips queued to streaming, clearing statusText', () => {
      const id = useChatStore.getState().addAssistantMessage()
      useChatStore.getState().setStatus(id, 'queued', 'In queue')

      useChatStore.getState().appendToken(id, 'Hello')
      useChatStore.getState().appendToken(id, ' world')

      const message = useChatStore.getState().messages[0]
      expect(message).toMatchObject({
        content: 'Hello world',
        status: 'streaming',
        statusText: undefined,
      })
    })

    it('flips processing to streaming on first token', () => {
      const id = useChatStore.getState().addAssistantMessage()
      useChatStore.getState().setStatus(id, 'processing', 'Generating response...')

      useChatStore.getState().appendToken(id, 'tok')

      expect(useChatStore.getState().messages[0]).toMatchObject({
        status: 'streaming',
        content: 'tok',
        statusText: undefined,
      })
    })

    it('preserves identity of non-targeted messages (memoization contract)', () => {
      const a = useChatStore.getState().addAssistantMessage()
      const b = useChatStore.getState().addAssistantMessage()
      const before = useChatStore.getState().messages

      useChatStore.getState().appendToken(a, 'x')

      const after = useChatStore.getState().messages
      // The targeted message gets a new object reference.
      expect(after[0]).not.toBe(before[0])
      // The non-targeted message keeps the same object reference.
      expect(after[1]).toBe(before[1])
      expect(after[1].id).toBe(b)
    })
  })

  describe('setSources', () => {
    it('populates sources on the named message', () => {
      const id = useChatStore.getState().addAssistantMessage()
      const sources = [
        {
          documentId: 1,
          documentName: 'Manual.docx',
          section: '3.2',
          page: 7,
          snippet: 'snippet',
        },
      ]

      useChatStore.getState().setSources(id, sources)

      expect(useChatStore.getState().messages[0].sources).toEqual(sources)
    })
  })

  describe('setError', () => {
    it('flips status to error and stores uxError', () => {
      const id = useChatStore.getState().addAssistantMessage()
      const uxError: ChatUxError = { kind: 'network_failure' }

      useChatStore.getState().setError(id, uxError)

      const message = useChatStore.getState().messages[0]
      expect(message.status).toBe('error')
      expect(message.uxError).toEqual(uxError)
    })

    it('is a no-op for unknown id', () => {
      const id = useChatStore.getState().addAssistantMessage()
      const before = useChatStore.getState().messages

      useChatStore.getState().setError('does-not-exist', { kind: 'network_failure' })

      const after = useChatStore.getState().messages
      expect(after[0]).toBe(before[0])
      expect(after[0].id).toBe(id)
      expect(after[0].status).toBe('queued')
      expect(after[0].uxError).toBeUndefined()
    })
  })

  describe('resetAssistantMessage', () => {
    it('clears content, sources, uxError, statusText and returns status to queued', () => {
      const id = useChatStore.getState().addAssistantMessage()
      const sources = [
        {
          documentId: 1,
          documentName: 'Manual.docx',
          section: null,
          page: null,
          snippet: 's',
        },
      ]
      useChatStore.getState().setStatus(id, 'streaming', 'should-clear')
      useChatStore.getState().appendToken(id, 'partial output')
      useChatStore.getState().setSources(id, sources)
      useChatStore.getState().setError(id, { kind: 'network_failure' })

      useChatStore.getState().resetAssistantMessage(id)

      const message = useChatStore.getState().messages[0]
      expect(message).toMatchObject({
        id,
        status: 'queued',
        content: '',
        sources: undefined,
        uxError: undefined,
        statusText: undefined,
      })
    })
  })

  describe('setIsStreaming', () => {
    it('toggles the isStreaming flag', () => {
      useChatStore.getState().setIsStreaming(true)
      expect(useChatStore.getState().isStreaming).toBe(true)
      useChatStore.getState().setIsStreaming(false)
      expect(useChatStore.getState().isStreaming).toBe(false)
    })
  })

  describe('clearAll', () => {
    it('empties messages and resets isStreaming', () => {
      useChatStore.getState().addUserMessage('hi')
      useChatStore.getState().setIsStreaming(true)

      useChatStore.getState().clearAll()

      const state = useChatStore.getState()
      expect(state.messages).toEqual([])
      expect(state.isStreaming).toBe(false)
    })
  })

  describe('persistence', () => {
    it('partialize strips in-flight messages and keeps done + error on save', async () => {
      const fixture: Message[] = [
        { id: '1', role: 'assistant', content: '', status: 'queued' },
        { id: '2', role: 'assistant', content: '', status: 'processing' },
        { id: '3', role: 'assistant', content: 'partial', status: 'streaming' },
        { id: '4', role: 'user', content: 'question', status: 'done' },
        {
          id: '5',
          role: 'assistant',
          content: 'oops',
          status: 'error',
          uxError: { kind: 'network_failure' },
        },
      ]

      useChatStore.setState({ messages: fixture, isStreaming: false })
      await flushPersist()

      const raw = sessionStorage.getItem(STORAGE_KEY)
      expect(raw).not.toBeNull()
      const parsed = JSON.parse(raw!) as { state: { messages: Message[] } }
      expect(parsed.state.messages.map((m) => m.id)).toEqual(['4', '5'])
      expect(parsed.state.messages.map((m) => m.status)).toEqual(['done', 'error'])
    })

    it('rehydrates persisted messages from sessionStorage', async () => {
      const stored: Message[] = [
        { id: 'u1', role: 'user', content: 'hi', status: 'done' },
        { id: 'a1', role: 'assistant', content: 'hello', status: 'done' },
      ]
      sessionStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ state: { messages: stored }, version: 0 }),
      )

      await useChatStore.persist.rehydrate()

      const state = useChatStore.getState()
      expect(state.messages).toEqual(stored)
      expect(state.isStreaming).toBe(false)
    })
  })
})
