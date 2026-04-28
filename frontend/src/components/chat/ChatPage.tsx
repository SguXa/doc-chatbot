import { useEffect, useRef } from 'react'
import { ChatSidebar } from './ChatSidebar'
import { ChatInput } from './ChatInput'
import { MessageList } from './MessageList'
import { BackfillBanner } from './BackfillBanner'
import { useChatStore } from '@/stores/chatStore'
import { useReadyStatus } from '@/hooks/useReadyStatus'
import { streamChat } from '@/api/chat'
import type { ChatRequestBody } from '@/api/chat'
import { mapHttpError, mapMidStreamError } from '@/lib/chatErrors'

interface RetryState {
  timeoutId: ReturnType<typeof setTimeout>
  intervalId: ReturnType<typeof setInterval>
  messageId: string
}

function ChatPage() {
  const isStreaming = useChatStore((s) => s.isStreaming)
  const { status } = useReadyStatus()
  const isBlocked = status === 'failed'

  const controllerRef = useRef<AbortController | null>(null)
  const retryRef = useRef<RetryState | null>(null)

  // cancelInFlight + runStream + handleSend + handleRetry are stable refs
  // (pure closures over the refs, not React state) so we declare them inline
  // without useCallback. Their identity does not affect any memoized child.

  const cancelInFlight = () => {
    if (retryRef.current) {
      clearTimeout(retryRef.current.timeoutId)
      clearInterval(retryRef.current.intervalId)
      retryRef.current = null
    }
    if (controllerRef.current) {
      controllerRef.current.abort()
      controllerRef.current = null
    }
  }

  useEffect(() => {
    let prevLength = useChatStore.getState().messages.length
    const unsub = useChatStore.subscribe((state) => {
      const next = state.messages.length
      if (prevLength > 0 && next === 0) {
        cancelInFlight()
      }
      prevLength = next
    })
    return () => {
      unsub()
      cancelInFlight()
    }
  }, [])

  const runStream = async (messageId: string, body: ChatRequestBody) => {
    cancelInFlight()
    const controller = new AbortController()
    controllerRef.current = controller
    const store = useChatStore.getState()
    store.setIsStreaming(true)
    try {
      for await (const event of streamChat(body, controller.signal)) {
        const s = useChatStore.getState()
        switch (event.type) {
          case 'queued':
            s.setStatus(
              messageId,
              'queued',
              `In queue (#${event.position}, ~${event.estimatedWait}s)…`,
            )
            break
          case 'processing':
            s.setStatus(messageId, 'processing', event.status)
            break
          case 'token':
            s.appendToken(messageId, event.text)
            break
          case 'sources':
            s.setSources(messageId, event.sources)
            break
          case 'done':
            s.setStatus(messageId, 'done')
            break
          case 'error':
            s.setError(messageId, mapMidStreamError(event.message))
            break
        }
      }
    } catch (error) {
      const ux = mapHttpError(error)
      if (ux.kind === 'backfill_running') {
        scheduleAutoRetry(messageId, body, ux.retryAfterSeconds)
        return
      }
      useChatStore.getState().setError(messageId, ux)
    } finally {
      if (!retryRef.current) {
        useChatStore.getState().setIsStreaming(false)
      }
      if (controllerRef.current === controller) {
        controllerRef.current = null
      }
    }
  }

  const scheduleAutoRetry = (
    messageId: string,
    body: ChatRequestBody,
    seconds: number,
  ) => {
    let remaining = seconds
    useChatStore
      .getState()
      .setStatus(messageId, 'queued', `Retrying in ${remaining}s…`)
    const intervalId = setInterval(() => {
      remaining -= 1
      if (remaining <= 0) return
      useChatStore
        .getState()
        .setStatus(messageId, 'queued', `Retrying in ${remaining}s…`)
    }, 1000)
    const timeoutId = setTimeout(() => {
      clearInterval(intervalId)
      retryRef.current = null
      runStream(messageId, body)
    }, seconds * 1000)
    retryRef.current = { timeoutId, intervalId, messageId }
  }

  const handleSend = (text: string) => {
    const snapshot = useChatStore.getState().messages
    const history = snapshot
      .filter((m) => m.status === 'done')
      .map((m) => ({ role: m.role, content: m.content }))
      .slice(-20)
    const store = useChatStore.getState()
    store.addUserMessage(text)
    const assistantId = store.addAssistantMessage()
    runStream(assistantId, { message: text, history })
  }

  const handleRetry = (messageId: string) => {
    cancelInFlight()
    const messages = useChatStore.getState().messages
    const idx = messages.findIndex((m) => m.id === messageId)
    if (idx <= 0) return
    const userMessage = messages[idx - 1]
    if (userMessage.role !== 'user') return
    const text = userMessage.content
    const precedingUserId = userMessage.id
    useChatStore.getState().resetAssistantMessage(messageId)
    const after = useChatStore.getState().messages
    const history = after
      .filter((m) => m.status === 'done' && m.id !== precedingUserId)
      .map((m) => ({ role: m.role, content: m.content }))
      .slice(-20)
    runStream(messageId, { message: text, history })
  }

  return (
    <div className="h-screen flex">
      <ChatSidebar />
      <main className="flex-1 flex flex-col overflow-hidden">
        <div className="max-w-3xl mx-auto w-full flex-1 flex flex-col min-h-0">
          {isBlocked ? (
            <div className="flex-1 flex items-center justify-center p-6">
              <div
                role="alert"
                className="border border-destructive bg-destructive/10 rounded p-6 max-w-md text-center"
              >
                Knowledge base unavailable. Please contact your administrator.
              </div>
            </div>
          ) : (
            <>
              <BackfillBanner />
              <div className="flex-1 min-h-0">
                <MessageList onRetry={handleRetry} />
              </div>
              <ChatInput onSend={handleSend} disabled={isStreaming} />
            </>
          )}
        </div>
      </main>
    </div>
  )
}

export { ChatPage }
