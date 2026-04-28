import { memo } from 'react'
import { User } from 'lucide-react'
import type { Message } from '@/stores/chatStore'

const UserMessage = memo(function UserMessage({
  message,
}: {
  message: Message
}) {
  return (
    <div className="flex gap-3 px-4 py-4">
      <div className="size-8 shrink-0 rounded-full bg-muted flex items-center justify-center">
        <User className="size-4" aria-hidden="true" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-xs text-muted-foreground mb-1">You</div>
        <div className="whitespace-pre-wrap">{message.content}</div>
      </div>
    </div>
  )
})

export { UserMessage }
