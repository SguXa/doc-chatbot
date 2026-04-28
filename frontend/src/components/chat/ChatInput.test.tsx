import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ChatInput } from './ChatInput'

describe('ChatInput', () => {
  it('updates the counter as the user types', () => {
    render(<ChatInput onSend={() => {}} disabled={false} />)
    const textarea = screen.getByPlaceholderText('Ask a question…')

    expect(screen.getByText('0 / 4000')).toBeInTheDocument()

    fireEvent.change(textarea, { target: { value: 'hello' } })
    expect(screen.getByText('5 / 4000')).toBeInTheDocument()
  })

  it('Enter calls onSend with trimmed text and clears input', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} disabled={false} />)
    const textarea = screen.getByPlaceholderText(
      'Ask a question…',
    ) as HTMLTextAreaElement

    fireEvent.change(textarea, { target: { value: '  hello world  ' } })
    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false })

    expect(onSend).toHaveBeenCalledTimes(1)
    expect(onSend).toHaveBeenCalledWith('hello world')
    expect(textarea.value).toBe('')
  })

  it('Shift+Enter inserts a newline and does NOT call onSend', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} disabled={false} />)
    const textarea = screen.getByPlaceholderText(
      'Ask a question…',
    ) as HTMLTextAreaElement

    fireEvent.change(textarea, { target: { value: 'line1' } })
    const event = fireEvent.keyDown(textarea, {
      key: 'Enter',
      shiftKey: true,
    })

    expect(onSend).not.toHaveBeenCalled()
    expect(event).toBe(true)
  })

  it('clicking Send calls onSend', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} disabled={false} />)
    const textarea = screen.getByPlaceholderText('Ask a question…')

    fireEvent.change(textarea, { target: { value: 'a question' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(onSend).toHaveBeenCalledWith('a question')
  })

  it('whitespace-only input does not trigger onSend; Send is disabled', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} disabled={false} />)
    const textarea = screen.getByPlaceholderText('Ask a question…')

    fireEvent.change(textarea, { target: { value: '   \n  ' } })
    const sendButton = screen.getByRole('button', { name: 'Send' })
    expect(sendButton).toBeDisabled()

    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('text > 4000 chars disables Send and reflects in the counter', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} disabled={false} />)
    const textarea = screen.getByPlaceholderText('Ask a question…')

    const longText = 'a'.repeat(4001)
    fireEvent.change(textarea, { target: { value: longText } })

    expect(screen.getByText('4001 / 4000')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()

    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('disabled={true} disables Send regardless of input', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} disabled={true} />)
    const textarea = screen.getByPlaceholderText('Ask a question…')

    fireEvent.change(textarea, { target: { value: 'hello' } })

    const sendButton = screen.getByRole('button', { name: 'Send' })
    expect(sendButton).toBeDisabled()

    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false })
    expect(onSend).not.toHaveBeenCalled()
  })
})
