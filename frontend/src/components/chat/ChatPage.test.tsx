import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ChatPage } from './ChatPage'
import * as useReadyStatusModule from '@/hooks/useReadyStatus'
import type { BackfillStatus } from '@/api/admin'
import { useChatStore } from '@/stores/chatStore'

function mockReadyStatus(status: BackfillStatus) {
  vi.spyOn(useReadyStatusModule, 'useReadyStatus').mockReturnValue({
    status,
    isRunning: status === 'running',
  })
}

describe('ChatPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    useChatStore.setState({ messages: [], isStreaming: false })
  })

  it('renders without crashing', () => {
    mockReadyStatus('idle')
    const { container } = render(<ChatPage />)
    expect(container).toBeTruthy()
  })

  it('has both <main> and <aside> landmarks', () => {
    mockReadyStatus('idle')
    const { container } = render(<ChatPage />)
    expect(container.querySelector('aside')).not.toBeNull()
    expect(container.querySelector('main')).not.toBeNull()
  })

  it('chat content wrapper has max-w-3xl', () => {
    mockReadyStatus('idle')
    const { container } = render(<ChatPage />)
    const main = container.querySelector('main')
    expect(main).not.toBeNull()
    const wrapper = main!.querySelector('.max-w-3xl')
    expect(wrapper).not.toBeNull()
  })

  it('shows the backfill banner and chat surface when status is running', () => {
    mockReadyStatus('running')
    render(<ChatPage />)
    expect(
      screen.getByText(/knowledge base is being prepared/i),
    ).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/ask a question/i)).toBeInTheDocument()
    expect(
      screen.queryByText(/knowledge base unavailable/i),
    ).not.toBeInTheDocument()
  })

  it('renders the blocker card when status is failed; hides MessageList and ChatInput', () => {
    mockReadyStatus('failed')
    render(<ChatPage />)
    expect(
      screen.getByText(/knowledge base unavailable/i),
    ).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/ask a question/i)).not.toBeInTheDocument()
    expect(
      screen.queryByText(/ask a question about aos documentation/i),
    ).not.toBeInTheDocument()
  })

  it('still renders the sidebar in the blocker state', () => {
    mockReadyStatus('failed')
    const { container } = render(<ChatPage />)
    expect(container.querySelector('aside')).not.toBeNull()
  })
})
