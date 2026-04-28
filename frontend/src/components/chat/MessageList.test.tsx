import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { MessageList, __messageRowRenderCounts } from './MessageList'
import { useChatStore } from '@/stores/chatStore'

function setSize(el: HTMLElement, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(el, 'scrollHeight', {
    configurable: true,
    value: scrollHeight,
  })
  Object.defineProperty(el, 'clientHeight', {
    configurable: true,
    value: clientHeight,
  })
}

describe('MessageList', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [], isStreaming: false })
    sessionStorage.clear()
    __messageRowRenderCounts.clear()
  })

  it('renders empty state when there are no messages', () => {
    render(<MessageList />)
    expect(
      screen.getByText('Ask a question about AOS documentation.'),
    ).toBeInTheDocument()
  })

  it('renders message content for each message', () => {
    useChatStore.setState({
      messages: [
        { id: '1', role: 'user', content: 'hello', status: 'done' },
        { id: '2', role: 'assistant', content: 'hi there', status: 'done' },
      ],
      isStreaming: false,
    })

    render(<MessageList />)
    expect(screen.getByText('hello')).toBeInTheDocument()
    expect(screen.getByText('hi there')).toBeInTheDocument()
  })

  it('shows the "Jump to latest" button when scrolled up by more than 20px from bottom', () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'x', status: 'done' }],
      isStreaming: false,
    })
    render(<MessageList />)

    const log = screen.getByRole('log')
    setSize(log, 1000, 500)
    log.scrollTop = 400 // distance = 1000 - 500 - 400 = 100 > 20
    fireEvent.scroll(log)

    expect(
      screen.getByRole('button', { name: /jump to latest/i }),
    ).toBeInTheDocument()
  })

  it('does NOT show the "Jump to latest" button when within 20px of bottom', () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'x', status: 'done' }],
      isStreaming: false,
    })
    render(<MessageList />)

    const log = screen.getByRole('log')
    setSize(log, 1000, 500)
    log.scrollTop = 490 // distance = 1000 - 500 - 490 = 10 <= 20
    fireEvent.scroll(log)

    expect(
      screen.queryByRole('button', { name: /jump to latest/i }),
    ).not.toBeInTheDocument()
  })

  it('clicking "Jump to latest" scrolls to bottom and hides the button', () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'x', status: 'done' }],
      isStreaming: false,
    })
    render(<MessageList />)

    const log = screen.getByRole('log')
    setSize(log, 1000, 500)
    log.scrollTop = 200
    fireEvent.scroll(log)

    fireEvent.click(screen.getByRole('button', { name: /jump to latest/i }))

    expect(log.scrollTop).toBe(500) // 1000 - 500
    expect(
      screen.queryByRole('button', { name: /jump to latest/i }),
    ).not.toBeInTheDocument()
  })

  it('appending a new message while pinned updates scrollTop to the new bottom', () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'x', status: 'done' }],
      isStreaming: false,
    })
    render(<MessageList />)

    const log = screen.getByRole('log')
    // user is at the bottom (initial state)
    setSize(log, 1000, 500)

    act(() => {
      useChatStore.getState().addUserMessage('next')
    })

    expect(log.scrollTop).toBe(500) // 1000 - 500
  })

  it('appending a new message while user scrolled up does NOT update scrollTop', () => {
    useChatStore.setState({
      messages: [{ id: '1', role: 'user', content: 'x', status: 'done' }],
      isStreaming: false,
    })
    render(<MessageList />)

    const log = screen.getByRole('log')
    setSize(log, 1000, 500)
    log.scrollTop = 100
    fireEvent.scroll(log) // distance = 400 > 20 → not at bottom

    setSize(log, 1500, 500)
    act(() => {
      useChatStore.getState().addUserMessage('next')
    })

    expect(log.scrollTop).toBe(100)
  })

  it('memoization: appending a token to the streaming message does NOT re-render a prior done message', () => {
    useChatStore.setState({
      messages: [
        { id: 'done1', role: 'user', content: 'q', status: 'done' },
        {
          id: 'streaming1',
          role: 'assistant',
          content: 'partial',
          status: 'streaming',
        },
      ],
      isStreaming: true,
    })
    render(<MessageList />)

    expect(__messageRowRenderCounts.get('done1')).toBe(1)
    expect(__messageRowRenderCounts.get('streaming1')).toBe(1)

    act(() => {
      useChatStore.getState().appendToken('streaming1', ' more')
    })

    expect(__messageRowRenderCounts.get('done1')).toBe(1)
    expect(__messageRowRenderCounts.get('streaming1')).toBe(2)
  })
})
