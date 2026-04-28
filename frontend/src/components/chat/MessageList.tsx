import { memo, useLayoutEffect, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { useChatStore, type Message } from '@/stores/chatStore'
import { Button } from '@/components/ui/button'
import { EmptyState } from './EmptyState'

const SCROLL_THRESHOLD_PX = 20

// Test scaffolding: per-message render counter so MessageList.test.tsx can
// assert React.memo + store-identity invariants. One Map.set per row render.
const __messageRowRenderCounts = new Map<string, number>()

const MessageRow = memo(function MessageRow({ message }: { message: Message }) {
  __messageRowRenderCounts.set(
    message.id,
    (__messageRowRenderCounts.get(message.id) ?? 0) + 1,
  )
  switch (message.role) {
    case 'user':
    case 'assistant':
    default:
      return <div data-message-id={message.id}>{message.content}</div>
  }
})

function MessageList() {
  const messages = useChatStore((s) => s.messages)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const [isAtBottom, setIsAtBottom] = useState(true)

  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el || !isAtBottom) return
    el.scrollTop = el.scrollHeight - el.clientHeight
  })

  const handleScroll = () => {
    const el = containerRef.current
    if (!el) return
    const distance = el.scrollHeight - el.clientHeight - el.scrollTop
    setIsAtBottom(distance <= SCROLL_THRESHOLD_PX)
  }

  const jumpToLatest = () => {
    const el = containerRef.current
    if (!el) return
    el.scrollTop = el.scrollHeight - el.clientHeight
    setIsAtBottom(true)
  }

  if (messages.length === 0) {
    return <EmptyState />
  }

  return (
    <div className="relative h-full">
      <div
        ref={containerRef}
        onScroll={handleScroll}
        role="log"
        className="h-full overflow-y-auto"
      >
        {messages.map((m) => (
          <MessageRow key={m.id} message={m} />
        ))}
      </div>
      {!isAtBottom && (
        <div className="absolute bottom-2 left-1/2 -translate-x-1/2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={jumpToLatest}
          >
            <ChevronDown />
            Jump to latest
          </Button>
        </div>
      )}
    </div>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export { MessageList, __messageRowRenderCounts }
