import { Routes, Route } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { apiGet } from './api/client'

interface HealthResponse {
  status: string
}

function HomePage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['health'],
    queryFn: () => apiGet<HealthResponse>('/api/health'),
    retry: false,
    refetchInterval: 30000,
  })

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="text-center">
        <h1 className="text-3xl font-bold text-gray-900">
          AOS Documentation Chatbot
        </h1>
        <p className="mt-2 text-gray-600">
          Ask questions about AOS technical documentation
        </p>
        <p className="mt-4 text-sm text-gray-500">
          Backend status:{' '}
          {isLoading && <span>checking...</span>}
          {isError && <span className="text-red-500">unavailable</span>}
          {!isError && data && <span className="text-green-600">{data.status}</span>}
        </p>
      </div>
    </div>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
    </Routes>
  )
}

export { App }
