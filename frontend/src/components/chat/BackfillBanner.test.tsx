import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { BackfillBanner } from './BackfillBanner'
import * as useReadyStatusModule from '@/hooks/useReadyStatus'
import type { BackfillStatus } from '@/api/admin'

function mockReadyStatus(status: BackfillStatus) {
  vi.spyOn(useReadyStatusModule, 'useReadyStatus').mockReturnValue({
    status,
    isRunning: status === 'running',
  })
}

describe('BackfillBanner', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders when status is running', () => {
    mockReadyStatus('running')
    render(<BackfillBanner />)
    expect(
      screen.getByText(/knowledge base is being prepared/i),
    ).toBeInTheDocument()
  })

  it('renders nothing when status is idle', () => {
    mockReadyStatus('idle')
    const { container } = render(<BackfillBanner />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when status is ready', () => {
    mockReadyStatus('ready')
    const { container } = render(<BackfillBanner />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when status is failed', () => {
    mockReadyStatus('failed')
    const { container } = render(<BackfillBanner />)
    expect(container).toBeEmptyDOMElement()
  })
})
