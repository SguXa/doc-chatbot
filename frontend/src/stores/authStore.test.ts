import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore, TOKEN_KEY } from './authStore'

describe('authStore', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ token: null, isAuthenticated: false })
  })

  describe('login', () => {
    it('sets token, flips isAuthenticated to true, and writes localStorage', () => {
      useAuthStore.getState().login('jwt-token')

      const state = useAuthStore.getState()
      expect(state.token).toBe('jwt-token')
      expect(state.isAuthenticated).toBe(true)
      expect(localStorage.getItem(TOKEN_KEY)).toBe('jwt-token')
    })
  })

  describe('logout', () => {
    it('clears token, flips isAuthenticated to false, and removes localStorage', () => {
      useAuthStore.getState().login('jwt-token')
      expect(localStorage.getItem(TOKEN_KEY)).toBe('jwt-token')

      useAuthStore.getState().logout()

      const state = useAuthStore.getState()
      expect(state.token).toBeNull()
      expect(state.isAuthenticated).toBe(false)
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    })
  })

  describe('hydrate', () => {
    it('with token in localStorage, sets state authenticated', () => {
      localStorage.setItem(TOKEN_KEY, 'persisted-token')

      useAuthStore.getState().hydrate()

      const state = useAuthStore.getState()
      expect(state.token).toBe('persisted-token')
      expect(state.isAuthenticated).toBe(true)
    })

    it('with no token in localStorage, leaves state unauthenticated and does not write', () => {
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull()

      useAuthStore.getState().hydrate()

      const state = useAuthStore.getState()
      expect(state.token).toBeNull()
      expect(state.isAuthenticated).toBe(false)
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    })
  })
})
