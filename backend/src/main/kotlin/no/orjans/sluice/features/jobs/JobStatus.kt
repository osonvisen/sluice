package no.orjans.sluice.features.jobs

import io.swagger.v3.oas.annotations.media.Schema

/** See ADR 0009. Stored as text; the database has a matching check constraint. */
@Schema(enumAsRef = true)
enum class JobStatus {
    /** Waiting for its first run, or rerun manually. */
    PENDING,

    /** Claimed by a worker until `locked_until`. */
    RUNNING,
    SUCCEEDED,

    /** The last attempt failed with a temporary error; a new attempt is planned at `next_run_at`. */
    FAILED,

    /** Gave up. Needs a person. Always has a [DeadReason]. */
    DEAD,
    ;

    companion object {
        val RETRYABLE: Set<JobStatus> = setOf(FAILED, DEAD)
    }
}

@Schema(enumAsRef = true)
enum class DeadReason {
    /** The handler reported an error a retry cannot fix. */
    PERMANENT,

    /** Temporary errors until the maximum number of attempts was reached. */
    EXHAUSTED,
}
