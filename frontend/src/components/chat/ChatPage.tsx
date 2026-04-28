import { ChatSidebar } from './ChatSidebar'
import { ChatInput } from './ChatInput'
import { MessageList } from './MessageList'
import { BackfillBanner } from './BackfillBanner'
import { useChatStore } from '@/stores/chatStore'
import { useReadyStatus } from '@/hooks/useReadyStatus'

function ChatPage() {
  const isStreaming = useChatStore((s) => s.isStreaming)
  const { status } = useReadyStatus()
  const isBlocked = status === 'failed'
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
                <MessageList />
              </div>
              <ChatInput onSend={() => {}} disabled={isStreaming} />
            </>
          )}
        </div>
      </main>
    </div>
  )
}

export { ChatPage }
