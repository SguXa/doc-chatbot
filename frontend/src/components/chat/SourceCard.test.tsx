import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SourceCard } from './SourceCard'
import type { Source } from '@/api/chat'

function makeSource(overrides: Partial<Source> = {}): Source {
  return {
    documentId: 1,
    documentName: 'Manual.docx',
    section: '3.2',
    page: 5,
    snippet: 'A short snippet.',
    ...overrides,
  }
}

describe('SourceCard', () => {
  it('renders header with documentName, section, and page when all present', () => {
    render(<SourceCard source={makeSource()} />)
    expect(screen.getByText('Manual.docx · §3.2 · p.5')).toBeInTheDocument()
  })

  it('omits section from header when null', () => {
    render(<SourceCard source={makeSource({ section: null })} />)
    expect(screen.getByText('Manual.docx · p.5')).toBeInTheDocument()
  })

  it('omits section from header when empty string', () => {
    render(<SourceCard source={makeSource({ section: '' })} />)
    expect(screen.getByText('Manual.docx · p.5')).toBeInTheDocument()
  })

  it('omits page from header when null', () => {
    render(<SourceCard source={makeSource({ page: null })} />)
    expect(screen.getByText('Manual.docx · §3.2')).toBeInTheDocument()
  })

  it('omits both section and page when both null', () => {
    render(<SourceCard source={makeSource({ section: null, page: null })} />)
    expect(screen.getByText('Manual.docx')).toBeInTheDocument()
  })

  it('renders full snippet without a button when snippet length is <= 150', () => {
    const snippet = 'a'.repeat(150)
    render(<SourceCard source={makeSource({ snippet })} />)
    expect(screen.getByText(snippet)).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /show more/i }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /show less/i }),
    ).not.toBeInTheDocument()
  })

  it('truncates snippet and shows "Show more" button when length > 150', () => {
    const snippet = 'a'.repeat(151)
    render(<SourceCard source={makeSource({ snippet })} />)
    const truncated = 'a'.repeat(150) + '…'
    expect(screen.getByText(truncated)).toBeInTheDocument()
    expect(screen.queryByText(snippet)).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Show more' }),
    ).toBeInTheDocument()
  })

  it('expands to full snippet when "Show more" is clicked, then collapses on "Show less"', () => {
    const snippet = 'b'.repeat(151)
    render(<SourceCard source={makeSource({ snippet })} />)

    fireEvent.click(screen.getByRole('button', { name: 'Show more' }))
    expect(screen.getByText(snippet)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Show less' }),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show less' }))
    const truncated = 'b'.repeat(150) + '…'
    expect(screen.getByText(truncated)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Show more' }),
    ).toBeInTheDocument()
  })

  it('trims trailing whitespace before appending the ellipsis', () => {
    const snippet = 'word '.repeat(40)
    render(<SourceCard source={makeSource({ snippet })} />)
    const expectedTruncated = snippet.slice(0, 150).trimEnd() + '…'
    expect(screen.getByText(expectedTruncated)).toBeInTheDocument()
  })
})
