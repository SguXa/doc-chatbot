import { memo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Bot, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { formatChatUxError } from '@/lib/chatErrors'
import { SourceCard } from './SourceCard'
import type { Message } from '@/stores/chatStore'

interface AssistantMessageProps {
  message: Message
  onRetry?: (messageId: string) => void
}

const AssistantMessage = memo(function AssistantMessage({
  message,
  onRetry,
}: AssistantMessageProps) {
  return (
    <div className="flex gap-3 px-4 py-4 bg-muted/50">
      <div
        className="size-8 shrink-0 rounded-full flex items-center justify-center text-white"
        style={{ backgroundColor: 'var(--accent-magenta)' }}
      >
        <Bot className="size-4" aria-hidden="true" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-xs text-muted-foreground mb-1">AOS Assistant</div>
        <AssistantBody message={message} onRetry={onRetry} />
      </div>
    </div>
  )
})

function AssistantBody({
  message,
  onRetry,
}: {
  message: Message
  onRetry?: (messageId: string) => void
}) {
  if (message.status === 'queued' || message.status === 'processing') {
    return (
      <div className="flex items-center gap-2 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" aria-hidden="true" />
        <span>{message.statusText ?? 'Working…'}</span>
      </div>
    )
  }

  if (message.status === 'error') {
    const text = message.uxError
      ? formatChatUxError(message.uxError)
      : 'An error occurred.'
    return (
      <div className="space-y-2">
        <div className="border border-destructive bg-destructive/10 rounded p-3 text-sm">
          {text}
        </div>
        {onRetry && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => onRetry(message.id)}
          >
            Retry
          </Button>
        )}
      </div>
    )
  }

  return (
    <>
      <div className="prose prose-sm max-w-none dark:prose-invert">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {message.content}
        </ReactMarkdown>
      </div>
      {message.status === 'done' &&
        message.sources &&
        message.sources.length > 0 && (
          <div className="mt-4 space-y-2">
            <h4 className="text-sm font-medium">Sources</h4>
            {message.sources.map((source, idx) => (
              <SourceCard
                key={`${source.documentId}-${idx}`}
                source={source}
              />
            ))}
          </div>
        )}
    </>
  )
}

export { AssistantMessage }
