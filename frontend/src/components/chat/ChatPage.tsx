import { ChatSidebar } from './ChatSidebar'
import { ChatInput } from './ChatInput'

function ChatPage() {
  return (
    <div className="h-screen flex">
      <ChatSidebar />
      <main className="flex-1 flex flex-col overflow-hidden">
        <div className="max-w-3xl mx-auto w-full flex-1 flex flex-col">
          <div className="flex-1" />
          <ChatInput onSend={() => {}} disabled={false} />
        </div>
      </main>
    </div>
  )
}

export { ChatPage }
