import { render } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { ChatPage } from './ChatPage'

describe('ChatPage', () => {
  it('renders without crashing', () => {
    const { container } = render(<ChatPage />)
    expect(container).toBeTruthy()
  })

  it('has both <main> and <aside> landmarks', () => {
    const { container } = render(<ChatPage />)
    expect(container.querySelector('aside')).not.toBeNull()
    expect(container.querySelector('main')).not.toBeNull()
  })

  it('chat content wrapper has max-w-3xl', () => {
    const { container } = render(<ChatPage />)
    const main = container.querySelector('main')
    expect(main).not.toBeNull()
    const wrapper = main!.querySelector('.max-w-3xl')
    expect(wrapper).not.toBeNull()
  })
})
