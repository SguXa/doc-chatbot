import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import type { Source } from '@/api/chat'
import type { ChatUxError } from '@/lib/chatErrors'

type MessageStatus = 'queued' | 'processing' | 'streaming' | 'done' | 'error'

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: MessageStatus
  statusText?: string
  sources?: Source[]
  uxError?: ChatUxError
}

interface ChatState {
  messages: Message[]
  isStreaming: boolean
  addUserMessage: (content: string) => string
  addAssistantMessage: () => string
  setStatus: (messageId: string, status: MessageStatus, statusText?: string) => void
  appendToken: (messageId: string, text: string) => void
  setSources: (messageId: string, sources: Source[]) => void
  setError: (messageId: string, uxError: ChatUxError) => void
  resetAssistantMessage: (messageId: string) => void
  setIsStreaming: (value: boolean) => void
  clearAll: () => void
}

const STORAGE_KEY = 'aos.chat.session'

function generateId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `msg-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const useChatStore = create<ChatState>()(
  persist(
    (set) => ({
      messages: [],
      isStreaming: false,
      addUserMessage: (content) => {
        const id = generateId()
        set((state) => ({
          messages: [
            ...state.messages,
            { id, role: 'user', content, status: 'done' },
          ],
        }))
        return id
      },
      addAssistantMessage: () => {
        const id = generateId()
        set((state) => ({
          messages: [
            ...state.messages,
            { id, role: 'assistant', content: '', status: 'queued' },
          ],
        }))
        return id
      },
      setStatus: (messageId, status, statusText) =>
        set((state) => ({
          messages: state.messages.map((m) =>
            m.id === messageId ? { ...m, status, statusText } : m,
          ),
        })),
      appendToken: (messageId, text) =>
        set((state) => ({
          messages: state.messages.map((m) => {
            if (m.id !== messageId) return m
            const status: MessageStatus =
              m.status === 'queued' || m.status === 'processing'
                ? 'streaming'
                : m.status
            return {
              ...m,
              content: m.content + text,
              status,
              statusText: undefined,
            }
          }),
        })),
      setSources: (messageId, sources) =>
        set((state) => ({
          messages: state.messages.map((m) =>
            m.id === messageId ? { ...m, sources } : m,
          ),
        })),
      setError: (messageId, uxError) =>
        set((state) => ({
          messages: state.messages.map((m) =>
            m.id === messageId ? { ...m, status: 'error', uxError } : m,
          ),
        })),
      resetAssistantMessage: (messageId) =>
        set((state) => ({
          messages: state.messages.map((m) =>
            m.id === messageId
              ? {
                  ...m,
                  status: 'queued',
                  content: '',
                  sources: undefined,
                  uxError: undefined,
                  statusText: undefined,
                }
              : m,
          ),
        })),
      setIsStreaming: (value) => set({ isStreaming: value }),
      clearAll: () => set({ messages: [], isStreaming: false }),
    }),
    {
      name: STORAGE_KEY,
      storage: createJSONStorage(() => sessionStorage),
      partialize: (state) => ({
        messages: state.messages.filter(
          (m) => m.status === 'done' || m.status === 'error',
        ),
      }),
    },
  ),
)

export { useChatStore, STORAGE_KEY }
export type { Message, MessageStatus, ChatState }
