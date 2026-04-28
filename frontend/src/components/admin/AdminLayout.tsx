import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { apiPost } from '@/api/client'
import { useAuthStore } from '@/stores/authStore'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

const NAV_ITEMS = [
  { to: '/admin/documents', label: 'Documents' },
  { to: '/admin/system-prompt', label: 'System Prompt' },
] as const

function AdminLayout() {
  const navigate = useNavigate()
  const logout = useAuthStore((s) => s.logout)

  function handleLogout() {
    // Fire-and-forget per ADR 0007: the server is stateless, so logout
    // success is decided by the local store, not by the network. Awaiting
    // would tie sign-out to backend reachability — a slow or unreachable
    // /api/auth/logout would leave the user stuck inside protected UI.
    apiPost('/api/auth/logout').catch(() => {})
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen flex bg-background">
      <aside
        aria-label="Admin navigation"
        className="w-60 shrink-0 border-r bg-card flex flex-col"
      >
        <div className="px-4 py-5 border-b">
          <h1 className="text-base font-semibold">AOS Admin</h1>
        </div>
        <nav className="flex-1 p-2 flex flex-col gap-1">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  'rounded-md px-3 py-2 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-muted text-foreground'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                )
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="p-2 border-t">
          <Button
            variant="ghost"
            className="w-full justify-start"
            onClick={handleLogout}
          >
            <LogOut />
            Log out
          </Button>
        </div>
      </aside>
      <main className="flex-1 min-w-0 p-6">
        <Outlet />
      </main>
    </div>
  )
}

export { AdminLayout }
