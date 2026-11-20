package no.orjans.sluice.support

import no.orjans.sluice.features.jobs.IdempotencyKey
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobOutcome
import no.orjans.sluice.features.jobs.JobQueue
import no.orjans.sluice.features.jobs.worker.JobWorker
import org.springframework.transaction.support.TransactionTemplate

/** Puts jobs in a given state through the real queue and worker, with [ScriptedJobHandler] deciding the outcome. */
class TestJobs(
    private val queue: JobQueue,
    private val worker: JobWorker,
    private val handler: ScriptedJobHandler,
    private val transactions: TransactionTemplate,
) {
    fun pending(key: String): JobId =
        checkNotNull(transactions.execute { queue.enqueue(ScriptedJobHandler.TYPE, ScriptedPayload(key), IdempotencyKey(key)) }).jobId

    /** `DEAD` with `PERMANENT` after one attempt: the state "Run again" is for. */
    fun dead(
        key: String,
        reason: String = "bad data",
    ): JobId {
        handler.script(key, { JobOutcome.PermanentFailure(reason) })
        val id = pending(key)
        worker.runOnce()
        return id
    }
}
