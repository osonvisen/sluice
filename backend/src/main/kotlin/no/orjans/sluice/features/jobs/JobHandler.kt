package no.orjans.sluice.features.jobs

/**
 * Runs one kind of job. Implement it as a Spring bean and the worker finds it by [type].
 *
 * Runs inside a transaction that also marks the job `SUCCEEDED`: database writes made here are
 * committed only if the outcome is [JobOutcome.Succeeded], and rolled back otherwise (ADR 0009).
 * Must be idempotent: after a crash, the same job can run again.
 */
interface JobHandler<T : Any> {
    val type: JobType

    /** The payload is stored as JSON and read back into this type before [handle] is called. */
    val payloadType: Class<T>

    fun handle(payload: T): JobOutcome
}

/** What a [JobHandler] reports. Reasons are stored and shown to operators: never put personal data in them. */
sealed interface JobOutcome {
    data object Succeeded : JobOutcome

    /** May work later (timeout, 5xx, lock). Retried with backoff until the maximum number of attempts. */
    data class TemporaryFailure(
        val reason: String,
    ) : JobOutcome

    /** A retry cannot fix it (invalid data, unknown product). The job goes straight to `DEAD`. */
    data class PermanentFailure(
        val reason: String,
    ) : JobOutcome
}
