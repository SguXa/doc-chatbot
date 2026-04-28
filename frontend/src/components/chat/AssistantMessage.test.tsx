import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AssistantMessage } from './AssistantMessage'
import type { Message } from '@/stores/chatStore'
import type { Source } from '@/api/chat'

function makeMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'a1',
    role: 'assistant',
    content: '',
    status: 'queued',
    ...overrides,
  }
}

function makeSource(overrides: Partial<Source> = {}): Source {
  return {
    documentId: 1,
    documentName: 'Manual.docx',
    section: '3.2',
    page: 5,
    snippet: 'A snippet of text.',
    ...overrides,
  }
}

describe('AssistantMessage', () => {
  it('renders Loader2 and statusText for queued status', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({ status: 'queued', statusText: 'In queue (#1)…' })}
      />,
    )
    const spinner = container.querySelector('svg.lucide-loader-circle')
    expect(spinner).not.toBeNull()
    expect(screen.getByText('In queue (#1)…')).toBeInTheDocument()
  })

  it('falls back to "Working…" when statusText is missing on queued', () => {
    render(<AssistantMessage message={makeMessage({ status: 'queued' })} />)
    expect(screen.getByText('Working…')).toBeInTheDocument()
  })

  it('renders updated statusText for processing status', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({
          status: 'processing',
          statusText: 'Searching documents...',
        })}
      />,
    )
    const spinner = container.querySelector('svg.lucide-loader-circle')
    expect(spinner).not.toBeNull()
    expect(screen.getByText('Searching documents...')).toBeInTheDocument()
  })

  it('renders markdown bold for streaming content', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({
          status: 'streaming',
          content: 'This is **bold** text.',
        })}
      />,
    )
    const strong = container.querySelector('strong')
    expect(strong).not.toBeNull()
    expect(strong?.textContent).toBe('bold')
  })

  it('renders fenced code blocks as pre/code', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({
          status: 'streaming',
          content: '```\nconst x = 1;\n```',
        })}
      />,
    )
    const pre = container.querySelector('pre')
    const code = container.querySelector('pre code')
    expect(pre).not.toBeNull()
    expect(code).not.toBeNull()
    expect(code?.textContent).toContain('const x = 1;')
  })

  it('renders GFM tables as <table>', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({
          status: 'streaming',
          content:
            '| col1 | col2 |\n| --- | --- |\n| a | b |\n',
        })}
      />,
    )
    const table = container.querySelector('table')
    expect(table).not.toBeNull()
    expect(table?.querySelectorAll('th').length).toBe(2)
    expect(table?.querySelectorAll('td').length).toBe(2)
  })

  it('renders markdown content and a "Sources" heading when done with sources', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({
          status: 'done',
          content: 'Answer with **markdown**.',
          sources: [makeSource()],
        })}
      />,
    )
    expect(container.querySelector('strong')?.textContent).toBe('markdown')
    expect(screen.getByText('Sources')).toBeInTheDocument()
    expect(screen.getByText('Manual.docx')).toBeInTheDocument()
  })

  it('does not render Sources block when done with empty sources', () => {
    render(
      <AssistantMessage
        message={makeMessage({
          status: 'done',
          content: 'Answer.',
          sources: [],
        })}
      />,
    )
    expect(screen.queryByText('Sources')).not.toBeInTheDocument()
  })

  it('does not render Sources block when done with undefined sources', () => {
    render(
      <AssistantMessage
        message={makeMessage({ status: 'done', content: 'Answer.' })}
      />,
    )
    expect(screen.queryByText('Sources')).not.toBeInTheDocument()
  })

  it('renders the formatted UX error and a Retry button on error status', () => {
    const onRetry = vi.fn()
    render(
      <AssistantMessage
        message={makeMessage({
          status: 'error',
          uxError: { kind: 'network_failure' },
        })}
        onRetry={onRetry}
      />,
    )
    expect(
      screen.getByText('Unable to reach server. Please check your connection.'),
    ).toBeInTheDocument()
    const retryBtn = screen.getByRole('button', { name: 'Retry' })
    fireEvent.click(retryBtn)
    expect(onRetry).toHaveBeenCalledWith('a1')
  })

  it('omits the Retry button when onRetry is not provided', () => {
    render(
      <AssistantMessage
        message={makeMessage({
          status: 'error',
          uxError: { kind: 'queue_unavailable' },
        })}
      />,
    )
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('escapes HTML and does NOT render <script> tags from content', () => {
    const { container } = render(
      <AssistantMessage
        message={makeMessage({
          status: 'streaming',
          content: '<script>alert(1)</script>',
        })}
      />,
    )
    expect(container.querySelector('script')).toBeNull()
    expect(container.textContent).toContain('<script>alert(1)</script>')
  })

  it('uses var(--accent-magenta) for the assistant avatar background', () => {
    const { container } = render(
      <AssistantMessage message={makeMessage({ status: 'queued' })} />,
    )
    const avatar = container.querySelector('svg.lucide-bot')?.parentElement
    expect(avatar).not.toBeNull()
    expect(avatar?.getAttribute('style')).toContain('var(--accent-magenta)')
  })
})
