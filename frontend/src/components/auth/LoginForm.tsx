import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Loader2 } from 'lucide-react'
import { apiPost } from '@/api/client'
import { useAuthStore } from '@/stores/authStore'
import { parseApiError, type ParsedError } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

interface LoginResponse {
  token: string
  expiresIn: number
  user: { username: string; role: string }
}

interface LocationState {
  from?: { pathname: string }
}

function LoginForm() {
  const navigate = useNavigate()
  const location = useLocation()
  const login = useAuthStore((s) => s.login)

  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<ParsedError | null>(null)

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (password.length === 0) {
      setError({ kind: 'empty_password', message: 'Enter password' })
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const response = await apiPost<LoginResponse>('/api/auth/login', {
        username: 'admin',
        password,
      })
      login(response.token)
      const target =
        (location.state as LocationState | null)?.from?.pathname ??
        '/admin/documents'
      navigate(target, { replace: true })
    } catch (err) {
      setError(parseApiError(err))
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-background p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Sign in</CardTitle>
          <CardDescription>Enter the admin password to continue.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                autoFocus
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={submitting}
                aria-invalid={error !== null}
              />
            </div>
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error.message}
              </p>
            )}
            <Button type="submit" disabled={submitting}>
              {submitting && <Loader2 className="animate-spin" />}
              {submitting ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}

export { LoginForm }
