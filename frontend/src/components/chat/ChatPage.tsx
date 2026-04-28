import { useCallback, useEffect, useRef } from 'react'
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

  // Stable handler identities: callbacks close only over refs and store
  // accessors (no React state), so empty deps keep MessageRow.memo intact
  // when ChatPage re-renders on isStreaming/readiness transitions.

  const cancelInFlight = useCallback(() => {
    if (retryRef.current) {
      clearTimeout(retryRef.current.timeoutId)
      clearInterval(retryRef.current.intervalId)
      retryRef.current = null
    }
    if (controllerRef.current) {
      controllerRef.current.abort()
      controllerRef.current = null
    }
  }, [])

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
  }, [cancelInFlight])

  const runStream = useCallback(
    async (messageId: string, body: ChatRequestBody) => {
      cancelInFlight()
      const controller = new AbortController()
      controllerRef.current = controller
      useChatStore.getState().setIsStreaming(true)
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
              return
            case 'error':
              s.setError(messageId, mapMidStreamError(event.message))
              return
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
        if (controllerRef.current === controller) {
          controllerRef.current = null
        }
        // Clear isStreaming only when no newer run has replaced us. Three
        // paths reach here:
        //   1. normal completion → controllerRef just nulled above.
        //   2. external cancel (clearAll / unmount) → controllerRef already
        //      nulled by cancelInFlight before our for-await resolved.
        //   3. replaced by a newer runStream (e.g. handleRetry mid-stream)
        //      → controllerRef points to the new controller; do not clobber.
        if (controllerRef.current === null && !retryRef.current) {
          useChatStore.getState().setIsStreaming(false)
        }
      }
    },
    // scheduleAutoRetry is declared below and references runStream itself; we
    // intentionally omit it from deps to keep this callback's identity stable
    // (the closure reaches scheduleAutoRetry via the lexical scope at call time).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [cancelInFlight],
  )

  const scheduleAutoRetry = useCallback(
    (messageId: string, body: ChatRequestBody, seconds: number) => {
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
    },
    [runStream],
  )

  const handleSend = useCallback(
    (text: string) => {
      const snapshot = useChatStore.getState().messages
      const history = snapshot
        .filter((m) => m.status === 'done')
        .map((m) => ({ role: m.role, content: m.content }))
        .slice(-20)
      const store = useChatStore.getState()
      store.addUserMessage(text)
      const assistantId = store.addAssistantMessage()
      runStream(assistantId, { message: text, history })
    },
    [runStream],
  )

  const handleRetry = useCallback(
    (messageId: string) => {
      // Any other in-progress assistant row would otherwise be left orphaned
      // when we abort the current stream / pending auto-retry. Mark it as
      // error so the user has a recovery path (and a Retry button) on it too.
      const before = useChatStore.getState().messages
      for (const m of before) {
        if (
          m.id !== messageId &&
          m.role === 'assistant' &&
          (m.status === 'queued' ||
            m.status === 'processing' ||
            m.status === 'streaming')
        ) {
          useChatStore.getState().setError(m.id, {
            kind: 'mid_stream',
            message: 'Cancelled by retry',
          })
        }
      }
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
    },
    [cancelInFlight, runStream],
  )

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
