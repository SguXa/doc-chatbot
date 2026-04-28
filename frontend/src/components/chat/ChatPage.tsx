import { ChatSidebar } from './ChatSidebar'
import { ChatInput } from './ChatInput'
import { MessageList } from './MessageList'
import { useChatStore } from '@/stores/chatStore'

function ChatPage() {
  const isStreaming = useChatStore((s) => s.isStreaming)
  return (
    <div className="h-screen flex">
      <ChatSidebar />
      <main className="flex-1 flex flex-col overflow-hidden">
        <div className="max-w-3xl mx-auto w-full flex-1 flex flex-col min-h-0">
          <div className="flex-1 min-h-0">
            <MessageList />
          </div>
          <ChatInput onSend={() => {}} disabled={isStreaming} />
        </div>
      </main>
    </div>
  )
}

export { ChatPage }
