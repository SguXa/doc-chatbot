import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AdminLayout } from './AdminLayout'
import { useAuthStore } from '@/stores/authStore'
import * as apiClient from '@/api/client'

function renderLayout(initialEntries: string[] = ['/admin/documents']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/admin" element={<AdminLayout />}>
          <Route path="documents" element={<div>Documents Page</div>} />
          <Route path="system-prompt" element={<div>Prompt Page</div>} />
        </Route>
        <Route path="/login" element={<div>Login Page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminLayout', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    useAuthStore.setState({ token: 't', isAuthenticated: true })
  })

  it('renders both nav links with correct hrefs', () => {
    renderLayout()

    const docsLink = screen.getByRole('link', { name: /documents/i })
    const promptLink = screen.getByRole('link', { name: /system prompt/i })

    expect(docsLink).toHaveAttribute('href', '/admin/documents')
    expect(promptLink).toHaveAttribute('href', '/admin/system-prompt')
  })

  it('renders the outlet content for the active route', () => {
    renderLayout(['/admin/documents'])
    expect(screen.getByText('Documents Page')).toBeInTheDocument()
  })

  it('marks the active nav link with aria-current="page" when route matches', () => {
    renderLayout(['/admin/system-prompt'])

    const promptLink = screen.getByRole('link', { name: /system prompt/i })
    const docsLink = screen.getByRole('link', { name: /documents/i })

    expect(promptLink).toHaveAttribute('aria-current', 'page')
    expect(docsLink).not.toHaveAttribute('aria-current', 'page')
  })

  it('logout button calls /api/auth/logout, clears auth, navigates to /login', async () => {
    const apiPostSpy = vi.spyOn(apiClient, 'apiPost').mockResolvedValue(undefined)

    renderLayout()

    fireEvent.click(screen.getByRole('button', { name: /log out/i }))

    await waitFor(() => {
      expect(screen.getByText('Login Page')).toBeInTheDocument()
    })

    expect(apiPostSpy).toHaveBeenCalledWith('/api/auth/logout')
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().token).toBeNull()
  })

  it('logout still clears local state when the API call rejects', async () => {
    vi.spyOn(apiClient, 'apiPost').mockRejectedValue(new Error('boom'))

    renderLayout()

    fireEvent.click(screen.getByRole('button', { name: /log out/i }))

    await waitFor(() => {
      expect(screen.getByText('Login Page')).toBeInTheDocument()
    })

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().token).toBeNull()
  })
})
