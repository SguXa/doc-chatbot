import { useState } from 'react'
import { PlaneTakeoff, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { useChatStore } from '@/stores/chatStore'

function ChatSidebar() {
  const [confirmOpen, setConfirmOpen] = useState(false)

  const handleNewChatClick = () => {
    if (useChatStore.getState().messages.length === 0) {
      return
    }
    setConfirmOpen(true)
  }

  const handleConfirm = () => {
    useChatStore.getState().clearAll()
    setConfirmOpen(false)
  }

  return (
    <aside className="w-60 shrink-0 border-r flex flex-col h-full">
      <div className="flex items-center gap-2 p-4">
        <PlaneTakeoff className="size-6 shrink-0" aria-hidden="true" />
        <span className="text-sm font-medium whitespace-pre-line">
          {'AOS Documentation\nChatbot'}
        </span>
      </div>
      <div className="px-3">
        <Button
          variant="outline"
          size="sm"
          className="w-full"
          onClick={handleNewChatClick}
        >
          <Plus />
          New chat
        </Button>
      </div>
      <div className="flex-1" />
      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Start a new chat?</AlertDialogTitle>
            <AlertDialogDescription>
              This will clear the current conversation. You can&apos;t undo
              this.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirm}>
              Continue
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </aside>
  )
}

export { ChatSidebar }
