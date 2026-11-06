package no.orjans.sluice.features.jobs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("sluice.jobs")
data class JobsProperties(
    /** Attempts before a job with temporary errors becomes `DEAD`/`EXHAUSTED`. */
    val maxAttempts: Int,
    /** Wait after the first failed attempt; doubled after each further failure. */
    val initialBackoff: Duration,
    /** Upper bound for the wait between attempts. */
    val maxBackoff: Duration,
    /** How long a worker may hold a job before it counts as crashed and the job is picked up again. */
    val lease: Duration,
    /** Jobs claimed per poll. */
    val batchSize: Int,
    val worker: Worker,
) {
    init {
        require(maxAttempts >= 1) { "sluice.jobs.max-attempts must be at least 1" }
        require(
            !initialBackoff.isNegative && initialBackoff <= maxBackoff,
        ) { "sluice.jobs: initial-backoff must be between 0 and max-backoff" }
        require(lease.isPositive) { "sluice.jobs.lease must be positive" }
        require(batchSize >= 1) { "sluice.jobs.batch-size must be at least 1" }
    }

    /** Exponential backoff: initial, 2×, 4×, … capped at [maxBackoff]. [failedAttempts] starts at 1. */
    fun backoffAfter(failedAttempts: Int): Duration {
        val doublings = (failedAttempts - 1).coerceIn(0, MAX_DOUBLINGS)
        val delay = initialBackoff.multipliedBy(1L shl doublings)
        return if (delay > maxBackoff) maxBackoff else delay
    }

    data class Worker(
        /** Off in tests (they run the worker by hand) and in the `migrate` profile. */
        val enabled: Boolean,
        val pollInterval: Duration,
    )

    private companion object {
        // 2^30 × any sensible initial backoff is far beyond any cap, and keeps the shift from overflowing.
        const val MAX_DOUBLINGS = 30
    }
}
