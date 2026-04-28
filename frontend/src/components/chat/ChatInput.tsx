import { useRef, useState, useLayoutEffect } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { Send } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'

const MAX_LENGTH = 4000
const MAX_ROWS = 6

interface ChatInputProps {
  onSend: (text: string) => void
  disabled: boolean
}

function ChatInput({ onSend, disabled }: ChatInputProps) {
  const [text, setText] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  const rows = Math.min(MAX_ROWS, Math.max(1, text.split('\n').length))

  useLayoutEffect(() => {
    const textarea = textareaRef.current
    if (!textarea) return
    if (rows >= MAX_ROWS) {
      textarea.style.overflowY = 'auto'
    } else {
      textarea.style.overflowY = 'hidden'
    }
  }, [rows])

  const trimmed = text.trim()
  const tooLong = text.length > MAX_LENGTH
  const sendDisabled = disabled || trimmed.length === 0 || tooLong

  const submit = () => {
    if (sendDisabled) return
    onSend(trimmed)
    setText('')
  }

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    submit()
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <form onSubmit={handleSubmit} className="border-t p-3">
      <div className="flex items-end gap-2">
        <Textarea
          ref={textareaRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Ask a question…"
          rows={rows}
          className="resize-none"
        />
        <Button type="submit" size="icon" aria-label="Send" disabled={sendDisabled}>
          <Send />
        </Button>
      </div>
      <div className="mt-1 flex justify-end">
        <span
          aria-live="polite"
          className={
            tooLong
              ? 'text-xs text-destructive'
              : 'text-xs text-muted-foreground'
          }
        >
          {text.length} / {MAX_LENGTH}
        </span>
      </div>
    </form>
  )
}

export { ChatInput }
