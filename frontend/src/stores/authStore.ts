import { create } from 'zustand'

const TOKEN_KEY = 'aos.token'

interface AuthState {
  token: string | null
  isAuthenticated: boolean
  login: (token: string) => void
  logout: () => void
}

const useAuthStore = create<AuthState>((set) => ({
  token: null,
  isAuthenticated: false,
  login: (token) => {
    localStorage.setItem(TOKEN_KEY, token)
    set({ token, isAuthenticated: true })
  },
  logout: () => {
    localStorage.removeItem(TOKEN_KEY)
    set({ token: null, isAuthenticated: false })
  },
}))

export { useAuthStore, TOKEN_KEY }
export type { AuthState }
