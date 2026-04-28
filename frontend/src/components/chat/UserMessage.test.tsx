import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { UserMessage } from './UserMessage'
import type { Message } from '@/stores/chatStore'

function makeMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'u1',
    role: 'user',
    content: 'hello world',
    status: 'done',
    ...overrides,
  }
}

describe('UserMessage', () => {
  it('renders the message content', () => {
    render(<UserMessage message={makeMessage({ content: 'hello world' })} />)
    expect(screen.getByText('hello world')).toBeInTheDocument()
  })

  it('renders the "You" label', () => {
    render(<UserMessage message={makeMessage()} />)
    expect(screen.getByText('You')).toBeInTheDocument()
  })

  it('renders the User icon avatar', () => {
    const { container } = render(<UserMessage message={makeMessage()} />)
    const icon = container.querySelector('svg.lucide-user')
    expect(icon).not.toBeNull()
  })
})
