import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchSystemPrompt, updateSystemPrompt } from '@/api/config'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Textarea } from '@/components/ui/textarea'
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
import { parseApiError } from '@/lib/errors'
import { cn } from '@/lib/utils'
import DEFAULT_PROMPT_CONSTANT from './__fixtures__/default-system-prompt.txt?raw'

const MAX_LENGTH = 8000

function SystemPromptPage() {
  const queryClient = useQueryClient()
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['system-prompt'],
    queryFn: fetchSystemPrompt,
    retry: false,
  })

  const [value, setValue] = useState('')
  const [serverPrompt, setServerPrompt] = useState<string | null>(null)
  const [resetOpen, setResetOpen] = useState(false)

  if (data && data.prompt !== serverPrompt) {
    setServerPrompt(data.prompt)
    setValue(data.prompt)
  }

  const mutation = useMutation({
    mutationFn: (prompt: string) => updateSystemPrompt(prompt),
    onSuccess: (response) => {
      queryClient.setQueryData(['system-prompt'], response)
      queryClient.invalidateQueries({ queryKey: ['system-prompt'] })
      setValue(response.prompt)
      toast.success('Saved')
    },
  })

  const isDirty = data ? value !== data.prompt : false
  const overLimit = value.length > MAX_LENGTH
  const isEmpty = value.trim() === ''
  const saveDisabled =
    !isDirty || isEmpty || overLimit || mutation.isPending || isLoading || isError

  function handleSave() {
    if (saveDisabled) return
    mutation.mutate(value)
  }

  function handleDiscard() {
    if (data) setValue(data.prompt)
  }

  function handleResetConfirm() {
    setValue(DEFAULT_PROMPT_CONSTANT)
    setResetOpen(false)
  }

  const mutationError = mutation.isError ? parseApiError(mutation.error) : null

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h2 className="text-xl font-semibold">System Prompt</h2>
        <p className="text-sm text-muted-foreground">
          Instructions sent to the LLM with every chat request.
        </p>
      </div>

      {isLoading && (
        <p className="text-sm text-muted-foreground">Loading system prompt…</p>
      )}

      {isError && (
        <Card className="ring-destructive/40">
          <CardHeader>
            <CardTitle className="text-destructive">Failed to load system prompt</CardTitle>
            <CardDescription>{parseApiError(error).message}</CardDescription>
          </CardHeader>
        </Card>
      )}

      {!isLoading && !isError && data && (
        <Card>
          <CardContent className="space-y-3 px-4">
            <Textarea
              aria-label="System prompt"
              className="font-mono min-h-[28rem]"
              rows={20}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              disabled={mutation.isPending}
            />
            <div className="flex items-center justify-between text-xs">
              <span
                aria-live="polite"
                className={cn(
                  'text-muted-foreground',
                  overLimit && 'text-destructive font-medium',
                )}
              >
                {value.length} / {MAX_LENGTH}
              </span>
              {mutationError && (
                <span role="alert" className="text-destructive">
                  {mutationError.message}
                </span>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button onClick={handleSave} disabled={saveDisabled}>
                {mutation.isPending ? 'Saving…' : 'Save'}
              </Button>
              {isDirty && (
                <Button
                  variant="outline"
                  onClick={handleDiscard}
                  disabled={mutation.isPending}
                >
                  Discard changes
                </Button>
              )}
              <Button
                variant="ghost"
                onClick={() => setResetOpen(true)}
                disabled={mutation.isPending}
              >
                Reset to default
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <AlertDialog open={resetOpen} onOpenChange={setResetOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reset to default?</AlertDialogTitle>
            <AlertDialogDescription>
              This loads the built-in prompt into the editor. Changes will not be saved
              until you click Save.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault()
                handleResetConfirm()
              }}
            >
              Reset
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

export { SystemPromptPage }
