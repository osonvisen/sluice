package no.orjans.sluice.features.jobs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.random.RandomGenerator

@ConfigurationProperties("sluice.jobs")
data class JobsProperties(
    /** Attempts before a job with temporary errors becomes `DEAD`/`EXHAUSTED`. */
    val maxAttempts: Int,
    /** Wait after the first failed attempt; doubled after each further failure. */
    val initialBackoff: Duration,
    /** Upper bound for the wait between attempts. */
    val maxBackoff: Duration,
    /**
     * Spread on each backoff, as a fraction: 0.2 gives ±20 %. Jobs that failed together (an ERP outage)
     * then retry spread out instead of all in the same second (ADR 0010).
     */
    val backoffJitter: Double,
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
        require(backoffJitter >= 0.0 && backoffJitter < 1.0) { "sluice.jobs.backoff-jitter must be in [0, 1)" }
        require(lease.isPositive) { "sluice.jobs.lease must be positive" }
        require(batchSize >= 1) { "sluice.jobs.batch-size must be at least 1" }
    }

    /**
     * Exponential backoff with jitter: initial, 2×, 4×, … each ± [backoffJitter], capped at [maxBackoff].
     * [failedAttempts] starts at 1. [random] is injected so tests can fix it.
     */
    fun backoffAfter(
        failedAttempts: Int,
        random: RandomGenerator,
    ): Duration {
        val doublings = (failedAttempts - 1).coerceIn(0, MAX_DOUBLINGS)
        val exponential = minOf(initialBackoff.multipliedBy(1L shl doublings), maxBackoff)
        // A factor in [1 - jitter, 1 + jitter). Double is fine here: this is a wait time, not money.
        val factor = 1 + backoffJitter * (2 * random.nextDouble() - 1)
        val jittered = Duration.ofMillis((exponential.toMillis() * factor).toLong())
        return minOf(jittered, maxBackoff)
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
