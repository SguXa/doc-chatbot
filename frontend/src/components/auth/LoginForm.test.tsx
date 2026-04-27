import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { LoginForm } from './LoginForm'
import { useAuthStore } from '@/stores/authStore'

function renderLogin(
  initialEntries: Array<string | { pathname: string; state: unknown }> = ['/login'],
) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/login" element={<LoginForm />} />
        <Route path="/admin/documents" element={<div>Documents Page</div>} />
        <Route path="/admin/system-prompt" element={<div>Prompt Page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function getPasswordInput() {
  return screen.getByLabelText(/password/i) as HTMLInputElement
}

function getSubmitButton() {
  return screen.getByRole('button', { name: /sign in/i }) as HTMLButtonElement
}

describe('LoginForm', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  it('renders password field and submit button', () => {
    renderLogin()
    expect(getPasswordInput()).toBeInTheDocument()
    expect(getSubmitButton()).toBeInTheDocument()
  })

  it('does not submit when password is empty', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    renderLogin()

    fireEvent.click(getSubmitButton())

    expect(fetchSpy).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(/enter password/i)
  })

  it('on success stores token and navigates to /admin/documents', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          token: 'jwt-token',
          expiresIn: 3600,
          user: { username: 'admin', role: 'admin' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    renderLogin()

    fireEvent.change(getPasswordInput(), { target: { value: 'secret' } })
    fireEvent.click(getSubmitButton())

    await waitFor(() => {
      expect(screen.getByText('Documents Page')).toBeInTheDocument()
    })
    expect(useAuthStore.getState().token).toBe('jwt-token')
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('sends correct POST body to /api/auth/login', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          token: 't',
          expiresIn: 3600,
          user: { username: 'admin', role: 'admin' },
        }),
        { status: 200 },
      ),
    )
    renderLogin()

    fireEvent.change(getPasswordInput(), { target: { value: 'mypw' } })
    fireEvent.click(getSubmitButton())

    await waitFor(() => expect(fetchSpy).toHaveBeenCalled())
    const call = fetchSpy.mock.calls[0]
    expect(call[0]).toBe('/api/auth/login')
    const init = call[1] as RequestInit
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ username: 'admin', password: 'mypw' }))
  })

  it('on 401 displays "Invalid password"', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ error: 'invalid_credentials' }), { status: 401 }),
    )
    renderLogin()

    fireEvent.change(getPasswordInput(), { target: { value: 'wrong' } })
    fireEvent.click(getSubmitButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(/invalid password/i)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('on 503 displays a server error message', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ error: 'service_unavailable' }), { status: 503 }),
    )
    renderLogin()

    fireEvent.change(getPasswordInput(), { target: { value: 'pw' } })
    fireEvent.click(getSubmitButton())

    const alert = await screen.findByRole('alert')
    expect(alert.textContent?.length ?? 0).toBeGreaterThan(0)
  })

  it('navigates to deeplink target when location.state.from is present', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          token: 't',
          expiresIn: 3600,
          user: { username: 'admin', role: 'admin' },
        }),
        { status: 200 },
      ),
    )
    renderLogin([
      { pathname: '/login', state: { from: { pathname: '/admin/system-prompt' } } },
    ])

    fireEvent.change(getPasswordInput(), { target: { value: 'pw' } })
    fireEvent.click(getSubmitButton())

    await waitFor(() => {
      expect(screen.getByText('Prompt Page')).toBeInTheDocument()
    })
  })
})
