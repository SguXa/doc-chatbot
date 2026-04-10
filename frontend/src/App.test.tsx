import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>{ui}</BrowserRouter>
    </QueryClientProvider>,
  )
}

describe('App', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders with router and shows title', () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'healthy' }), { status: 200 }),
    )
    renderWithProviders(<App />)
    expect(screen.getByText('AOS Documentation Chatbot')).toBeInTheDocument()
  })

  it('shows health status when backend is available', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ status: 'healthy' }), { status: 200 }),
    )
    renderWithProviders(<App />)
    expect(await screen.findByText('healthy')).toBeInTheDocument()
  })

  it('shows unavailable when backend is down', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('Network error'))
    renderWithProviders(<App />)
    expect(await screen.findByText('unavailable')).toBeInTheDocument()
  })
})
