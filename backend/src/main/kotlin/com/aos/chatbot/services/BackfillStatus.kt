package com.aos.chatbot.services

/**
 * Observable state of the embedding backfill job.
 *
 * Transitions: [Idle] → [Running] → ([Completed] | [Failed]).
 * The readiness probe at `/api/health/ready` maps these to its `backfill` field.
 */
sealed class BackfillStatus {
    /** Job has been constructed but never run. */
    data object Idle : BackfillStatus()

    /** Job is actively embedding chunks. */
    data class Running(val processed: Int, val total: Int) : BackfillStatus()

    /** Job finished. `embedded` + `skipped` == total chunks that had NULL embedding at start. */
    data class Completed(val embedded: Int, val skipped: Int) : BackfillStatus()

    /** Job aborted with an unrecoverable error (not an OllamaUnavailableException — those retry). */
    data class Failed(val message: String) : BackfillStatus()
}
