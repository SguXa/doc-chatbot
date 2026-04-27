import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as configApi from '@/api/config'
import * as sonner from 'sonner'
import { ApiError } from '@/api/client'
import { SystemPromptPage } from './SystemPromptPage'
import DEFAULT_PROMPT from './__fixtures__/default-system-prompt.txt?raw'

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <SystemPromptPage />
      </QueryClientProvider>,
    ),
  }
}

const SAMPLE = {
  prompt: 'You are an assistant. Be helpful.',
  updatedAt: '2026-04-27 10:00:00',
}

describe('SystemPromptPage', () => {
  beforeEach(() => {
    vi.spyOn(sonner.toast, 'success').mockImplementation(() => '' as never)
    vi.spyOn(sonner.toast, 'error').mockImplementation(() => '' as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the loading message initially', async () => {
    let resolve!: (v: typeof SAMPLE) => void
    vi.spyOn(configApi, 'fetchSystemPrompt').mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )

    renderPage()

    expect(screen.getByText(/loading system prompt/i)).toBeInTheDocument()

    resolve(SAMPLE)
    await waitFor(() => {
      expect(screen.queryByText(/loading system prompt/i)).not.toBeInTheDocument()
    })
  })

  it('renders the textarea with the loaded prompt and char counter', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)

    renderPage()

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    expect(textarea.value).toBe(SAMPLE.prompt)
    expect(
      screen.getByText(`${SAMPLE.prompt.length} / 8000`),
    ).toBeInTheDocument()
  })

  it('Save is disabled until the prompt is edited', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)

    renderPage()

    await screen.findByLabelText(/system prompt/i)
    const saveButton = screen.getByRole('button', { name: /^save$/i })
    expect(saveButton).toBeDisabled()
  })

  it('editing enables Save and calls updateSystemPrompt; query invalidates and toast shown', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)
    const updated = { prompt: 'new prompt', updatedAt: '2026-04-27 11:00:00' }
    const updateSpy = vi
      .spyOn(configApi, 'updateSystemPrompt')
      .mockResolvedValue(updated)

    const { queryClient } = renderPage()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: 'new prompt' } })

    expect(screen.getByText('10 / 8000')).toBeInTheDocument()
    const saveButton = screen.getByRole('button', { name: /^save$/i })
    expect(saveButton).not.toBeDisabled()

    fireEvent.click(saveButton)

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith('new prompt')
    })
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['system-prompt'] })
    })
    await waitFor(() => {
      expect(sonner.toast.success).toHaveBeenCalledWith('Saved')
    })
  })

  it('Discard reverts the textarea to the server value and hides itself', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)

    renderPage()

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: 'edited' } })

    const discard = screen.getByRole('button', { name: /discard changes/i })
    fireEvent.click(discard)

    expect(textarea.value).toBe(SAMPLE.prompt)
    expect(
      screen.queryByRole('button', { name: /discard changes/i }),
    ).not.toBeInTheDocument()
  })

  it('Reset opens dialog; confirm fills textarea with default; isDirty until Save', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)

    renderPage()

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    fireEvent.click(screen.getByRole('button', { name: /reset to default/i }))

    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: /^reset$/i })
    fireEvent.click(confirm)

    await waitFor(() => {
      expect(textarea.value).toBe(DEFAULT_PROMPT)
    })
    expect(screen.getByRole('button', { name: /^save$/i })).not.toBeDisabled()
  })

  it('empty prompt disables Save', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)

    renderPage()

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: '   ' } })

    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled()
  })

  it('400 empty_prompt mutation error renders inline message', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)
    vi.spyOn(configApi, 'updateSystemPrompt').mockRejectedValue(
      new ApiError(400, 'Bad Request', { error: 'invalid_request', reason: 'empty_prompt' }),
    )

    renderPage()

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: 'something' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    expect(
      await screen.findByText(/system prompt cannot be empty/i),
    ).toBeInTheDocument()
  })

  it('over-limit content turns counter red and disables Save', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockResolvedValue(SAMPLE)

    renderPage()

    const textarea = (await screen.findByLabelText(/system prompt/i)) as HTMLTextAreaElement
    const big = 'x'.repeat(8001)
    fireEvent.change(textarea, { target: { value: big } })

    const counter = screen.getByText('8001 / 8000')
    expect(counter.className).toMatch(/text-destructive/)
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled()
  })

  it('shows error card when the load fails', async () => {
    vi.spyOn(configApi, 'fetchSystemPrompt').mockRejectedValue(
      new ApiError(500, 'Internal Server Error', { error: 'config_missing' }),
    )

    renderPage()

    expect(
      await screen.findByText(/failed to load system prompt/i),
    ).toBeInTheDocument()
  })

  it('drift guard: DEFAULT_PROMPT matches V004-seeded default', async () => {
    // V004 stores the JSON-encoded form of this exact text. The fixture is
    // the decoded body. If V004 ever changes, regenerate the fixture.
    expect(DEFAULT_PROMPT).toContain('You are an AOS Documentation Assistant')
    expect(DEFAULT_PROMPT).toContain('Respond in German if the question is in German')
    // Sanity: matches the decoded payload from V004 byte-for-byte
    const expected =
      'You are an AOS Documentation Assistant. Your role is to help users find information \n' +
      'in the AOS technical documentation.\n' +
      '\n' +
      'Guidelines:\n' +
      '- Provide accurate, concise answers based on the documentation\n' +
      '- Always cite your sources with document name and section\n' +
      '- For troubleshooting codes (MA-XX), provide the full symptom, cause, and solution\n' +
      '- If information is not available, clearly state that\n' +
      '- Respond in German if the question is in German, otherwise in English\n' +
      '- Format code and technical terms appropriately'
    expect(DEFAULT_PROMPT).toBe(expected)
  })

})
