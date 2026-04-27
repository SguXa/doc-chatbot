import { render, screen } from '@testing-library/react'
import { describe, it, expect, beforeEach } from 'vitest'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import { ProtectedRoute } from './ProtectedRoute'
import { useAuthStore } from '@/stores/authStore'

interface LoginLocationState {
  from?: { pathname: string }
}

function LoginCapture({
  onLocation,
}: {
  onLocation: (loc: ReturnType<typeof useLocation>) => void
}) {
  const loc = useLocation()
  onLocation(loc)
  return <div>Login Page</div>
}

function renderProtected(
  initialEntries: string[],
  onLocation: (loc: ReturnType<typeof useLocation>) => void = () => {},
) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route element={<ProtectedRoute />}>
          <Route path="/admin/documents" element={<div>Protected Documents</div>} />
        </Route>
        <Route path="/login" element={<LoginCapture onLocation={onLocation} />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  it('renders the wrapped route when authenticated', () => {
    useAuthStore.setState({ token: 't', isAuthenticated: true })

    renderProtected(['/admin/documents'])

    expect(screen.getByText('Protected Documents')).toBeInTheDocument()
  })

  it('redirects to /login when unauthenticated', () => {
    renderProtected(['/admin/documents'])

    expect(screen.getByText('Login Page')).toBeInTheDocument()
    expect(screen.queryByText('Protected Documents')).not.toBeInTheDocument()
  })

  it('passes the original location as state.from on redirect (deeplink preservation)', () => {
    let captured: ReturnType<typeof useLocation> | null = null
    renderProtected(['/admin/documents'], (loc) => {
      captured = loc
    })

    expect(captured).not.toBeNull()
    const state = captured!.state as LoginLocationState
    expect(state.from?.pathname).toBe('/admin/documents')
  })
})
