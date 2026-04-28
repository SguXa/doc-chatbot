import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { ChatSidebar } from './ChatSidebar'
import { useChatStore } from '@/stores/chatStore'

describe('ChatSidebar', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isStreaming: false })
    sessionStorage.clear()
  })

  it('renders brand text and the PlaneTakeoff icon', () => {
    render(<ChatSidebar />)
    expect(
      screen.getByText(/AOS Documentation/i, { exact: false }),
    ).toBeInTheDocument()
    const icon = document.querySelector('svg.lucide-plane-takeoff')
    expect(icon).not.toBeNull()
  })

  it('renders the New chat button', () => {
    render(<ChatSidebar />)
    expect(
      screen.getByRole('button', { name: /new chat/i }),
    ).toBeInTheDocument()
  })

  it('clicking New chat with empty messages does nothing (no dialog, no clear)', () => {
    render(<ChatSidebar />)

    expect(useChatStore.getState().messages).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: /new chat/i }))

    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(useChatStore.getState().messages).toHaveLength(0)
  })

  it('clicking New chat with messages opens the AlertDialog', () => {
    useChatStore.getState().addUserMessage('hello')
    render(<ChatSidebar />)

    fireEvent.click(screen.getByRole('button', { name: /new chat/i }))

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toBeInTheDocument()
    expect(
      within(dialog).getByText(/start a new chat\?/i),
    ).toBeInTheDocument()
  })

  it('clicking Cancel closes the dialog without clearing messages', () => {
    useChatStore.getState().addUserMessage('hello')
    render(<ChatSidebar />)

    fireEvent.click(screen.getByRole('button', { name: /new chat/i }))
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /cancel/i }))

    expect(useChatStore.getState().messages).toHaveLength(1)
  })

  it('clicking Continue clears messages', () => {
    useChatStore.getState().addUserMessage('hello')
    useChatStore.getState().addAssistantMessage()
    render(<ChatSidebar />)

    fireEvent.click(screen.getByRole('button', { name: /new chat/i }))
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /continue/i }))

    expect(useChatStore.getState().messages).toEqual([])
  })
})
