import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { RunwayBackground } from './RunwayBackground'

describe('RunwayBackground', () => {
  it('renders an <svg> element', () => {
    const { container } = render(<RunwayBackground />)
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it('renders the magenta vanishing point using var(--accent-magenta)', () => {
    const { container } = render(<RunwayBackground />)
    const circles = container.querySelectorAll('circle')
    expect(circles.length).toBeGreaterThan(0)
    const magentaCircles = Array.from(circles).filter(
      (c) => c.getAttribute('fill') === 'var(--accent-magenta)',
    )
    expect(magentaCircles.length).toBeGreaterThan(0)
  })
})
