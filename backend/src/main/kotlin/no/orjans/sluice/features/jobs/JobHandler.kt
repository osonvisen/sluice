package no.orjans.sluice.features.jobs

import java.time.Duration

/**
 * Runs one kind of job. Implement it as a Spring bean and the worker finds it by [type].
 *
 * The worker runs a job in three steps (ADR 0010): claim (short transaction) → [handle] (no
 * transaction) → finish (short transaction, only if the worker still owns the job).
 *
 * Rules for [handle]:
 * - It may read from the database and call external systems, but must **not write** to the database.
 *   Writes go in the outcome's [JobEffects]; the worker applies them in the finish transaction,
 *   together with the job's new status, or discards them if the job was taken over meanwhile.
 * - It may run more than once for the same job (crash, expired lease). External calls must
 *   therefore be idempotent, e.g. by sending an idempotency key.
 */
interface JobHandler<T : Any> {
    val type: JobType

    /** The payload is stored as JSON and read back into this type before [handle] is called. */
    val payloadType: Class<T>

    fun handle(
        payload: T,
        context: JobContext,
    ): JobOutcome
}

/** Which job and attempt is running, e.g. to link log rows to the attempt. */
data class JobContext(
    val jobId: JobId,
    val attempt: Int,
    val attemptId: Long,
)

/** Database writes to make if the worker still owns the job when it finishes (ADR 0010). */
fun interface JobEffects {
    fun apply()

    companion object {
        val NONE = JobEffects { }
    }
}

/**
 * What a [JobHandler] reports. Every outcome can carry [effects]: a failed attempt also records
 * what happened (e.g. the per-line ERP log). Reasons are stored and shown to operators: never put
 * personal data in them.
 */
sealed interface JobOutcome {
    val effects: JobEffects

    data class Succeeded(
        override val effects: JobEffects = JobEffects.NONE,
    ) : JobOutcome

    /**
     * May work later (timeout, 5xx, rate limit). Retried with backoff until the maximum number of attempts.
     * [retryAfter] replaces the backoff when the other side said how long to wait (e.g. `Retry-After`);
     * the worker still never waits longer than `max-backoff`.
     */
    data class TemporaryFailure(
        val reason: String,
        override val effects: JobEffects = JobEffects.NONE,
        val retryAfter: Duration? = null,
    ) : JobOutcome

    /** A retry cannot fix it (invalid data, unknown product). The job goes straight to `DEAD`. */
    data class PermanentFailure(
        val reason: String,
        override val effects: JobEffects = JobEffects.NONE,
    ) : JobOutcome
}
