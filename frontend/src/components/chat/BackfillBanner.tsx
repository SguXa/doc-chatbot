import { useReadyStatus } from '@/hooks/useReadyStatus'

function BackfillBanner() {
  const { status } = useReadyStatus()
  if (status !== 'running') return null
  return (
    <div
      role="status"
      className="border-b border-yellow-300 bg-yellow-100 px-4 py-2 text-sm text-yellow-900"
    >
      Knowledge base is being prepared. You can ask questions, but expect a short wait.
    </div>
  )
}

export { BackfillBanner }
