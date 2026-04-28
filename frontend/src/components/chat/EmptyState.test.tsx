import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EmptyState } from './EmptyState'

describe('EmptyState', () => {
  it('renders the heading', () => {
    render(<EmptyState />)
    expect(
      screen.getByText('Ask a question about AOS documentation.'),
    ).toBeInTheDocument()
  })

  it('renders both example questions', () => {
    render(<EmptyState />)
    expect(screen.getByText('What is the MA-03 error code?')).toBeInTheDocument()
    expect(screen.getByText('How do I install component X?')).toBeInTheDocument()
  })
})
