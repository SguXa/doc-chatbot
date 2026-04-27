import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReindexButton } from './ReindexButton'
import * as adminApi from '@/api/admin'
import * as sonner from 'sonner'
import { ApiError } from '@/api/client'
import type { ReadyStatus } from '@/api/admin'

function renderButton() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ReindexButton />
      </QueryClientProvider>,
    ),
  }
}

const idleStatus: ReadyStatus = { backfill: { status: 'idle' } }
const runningStatus: ReadyStatus = { backfill: { status: 'running' } }

describe('ReindexButton', () => {
  beforeEach(() => {
    vi.spyOn(sonner.toast, 'success').mockImplementation(() => '' as never)
    vi.spyOn(sonner.toast, 'error').mockImplementation(() => '' as never)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('opens the confirmation dialog when clicked', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue(idleStatus)
    renderButton()

    const trigger = await screen.findByRole('button', { name: /reindex all/i })
    await waitFor(() => expect(trigger).not.toBeDisabled())
    fireEvent.click(trigger)

    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByText(/reindex all documents\?/i)).toBeInTheDocument()
  })

  it('calls reindex() and shows success toast on confirm', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue(idleStatus)
    const reindexSpy = vi
      .spyOn(adminApi, 'reindex')
      .mockResolvedValue({ status: 'started' })

    renderButton()

    const trigger = await screen.findByRole('button', { name: /reindex all/i })
    await waitFor(() => expect(trigger).not.toBeDisabled())
    fireEvent.click(trigger)

    const confirmButton = await screen.findByRole('button', { name: /^confirm$/i })
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(reindexSpy).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(sonner.toast.success).toHaveBeenCalledWith('Reindex started')
    })
  })

  it('treats 503 reindex_in_progress as success (idempotent)', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue(idleStatus)
    vi.spyOn(adminApi, 'reindex').mockRejectedValue(
      new ApiError(503, 'Service Unavailable', { error: 'reindex_in_progress' }),
    )

    renderButton()

    const trigger = await screen.findByRole('button', { name: /reindex all/i })
    await waitFor(() => expect(trigger).not.toBeDisabled())
    fireEvent.click(trigger)

    const confirmButton = await screen.findByRole('button', { name: /^confirm$/i })
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(sonner.toast.success).toHaveBeenCalledWith('Reindex started')
    })
  })

  it('shows error toast on non-503 failures', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue(idleStatus)
    vi.spyOn(adminApi, 'reindex').mockRejectedValue(
      new ApiError(500, 'Internal Server Error', { error: 'oops' }),
    )

    renderButton()

    const trigger = await screen.findByRole('button', { name: /reindex all/i })
    await waitFor(() => expect(trigger).not.toBeDisabled())
    fireEvent.click(trigger)

    const confirmButton = await screen.findByRole('button', { name: /^confirm$/i })
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(sonner.toast.error).toHaveBeenCalled()
    })
  })

  it('disables the button when reindex is running', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue(runningStatus)

    renderButton()

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /reindex all/i }),
      ).toBeDisabled()
    })
  })

  it('shows tooltip text via title attribute when disabled by running reindex', async () => {
    vi.spyOn(adminApi, 'fetchReady').mockResolvedValue(runningStatus)

    renderButton()

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: /reindex all/i }),
      ).toHaveAttribute('title', 'Reindex is running')
    })
  })
})
