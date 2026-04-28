import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { BrowserRouter, MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from './App'
import { useAuthStore } from '@/stores/authStore'

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

function renderAt(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('App', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  it('renders the chat page at /', () => {
    const { container } = renderWithProviders(<App />)
    expect(container.querySelector('main')).not.toBeNull()
    expect(container.querySelector('aside')).not.toBeNull()
  })

  it('redirects unauthenticated visit to /admin/documents to /login', () => {
    renderAt('/admin/documents')
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
  })

  it('lets authenticated user reach /admin/documents', () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ documents: [], total: 0 }), { status: 200 }),
    )
    useAuthStore.setState({ token: 't', isAuthenticated: true })
    renderAt('/admin/documents')
    expect(screen.getByRole('heading', { name: /^documents$/i })).toBeInTheDocument()
  })

  it('redirects /admin index route to /admin/documents when authenticated', () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ documents: [], total: 0 }), { status: 200 }),
    )
    useAuthStore.setState({ token: 't', isAuthenticated: true })
    renderAt('/admin')
    expect(screen.getByRole('heading', { name: /^documents$/i })).toBeInTheDocument()
  })
})
