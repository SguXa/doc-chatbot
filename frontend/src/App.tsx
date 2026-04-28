import { Routes, Route, Navigate } from 'react-router-dom'
import { LoginForm } from '@/components/auth/LoginForm'
import { ProtectedRoute } from '@/components/auth/ProtectedRoute'
import { AdminLayout } from '@/components/admin/AdminLayout'
import { DocumentsPage } from '@/components/admin/DocumentsPage'
import { SystemPromptPage } from '@/components/admin/SystemPromptPage'
import { ChatPage } from '@/components/chat/ChatPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/login" element={<LoginForm />} />
      <Route path="/admin" element={<ProtectedRoute />}>
        <Route element={<AdminLayout />}>
          <Route index element={<Navigate to="/admin/documents" replace />} />
          <Route path="documents" element={<DocumentsPage />} />
          <Route path="system-prompt" element={<SystemPromptPage />} />
        </Route>
      </Route>
    </Routes>
  )
}

export { App }
