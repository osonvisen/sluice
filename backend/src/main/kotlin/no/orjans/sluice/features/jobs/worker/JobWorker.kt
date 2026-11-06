package no.orjans.sluice.features.jobs.worker

import no.orjans.sluice.features.jobs.DeadReason
import no.orjans.sluice.features.jobs.JobContext
import no.orjans.sluice.features.jobs.JobHandler
import no.orjans.sluice.features.jobs.JobOutcome
import no.orjans.sluice.features.jobs.JobType
import no.orjans.sluice.features.jobs.JobsProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.random.RandomGenerator

/**
 * Runs due jobs. Each call to [runOnce] is one poll:
 * 1. Expired leases (crashed or stuck workers) count as a failed attempt.
 * 2. **Claim**: up to `batch-size` due jobs in one short transaction. Each gets a lease, and its
 *    version after the claim is the fencing token.
 * 3. **Work**: each handler runs *without* a transaction (it may call the ERP).
 * 4. **Finish**: one short transaction per job, that only changes anything if the fencing token
 *    still matches. The outcome's effects are applied in the same transaction (ADR 0010).
 */
@Component
class JobWorker(
    handlers: List<JobHandler<*>>,
    private val queries: WorkerQueries,
    private val transactions: TransactionTemplate,
    private val json: JsonMapper,
    private val clock: Clock,
    private val random: RandomGenerator,
    private val properties: JobsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Strategy by job type: a new type is a new JobHandler bean, the worker never changes.
    private val handlersByType: Map<JobType, JobHandler<*>> =
        handlers.groupBy { it.type }.mapValues { (type, found) ->
            check(found.size == 1) { "More than one JobHandler for job type '$type': ${found.map { it.javaClass.simpleName }}" }
            found.single()
        }

    /** @return the number of jobs claimed and run. */
    fun runOnce(): Int {
        expireLeases()
        val now = clock.instant()
        val claimed = transactions.execute { queries.claimDue(now, now.plus(properties.lease), properties.batchSize) }
        claimed.forEach { job -> finish(job, work(job)) }
        return claimed.size
    }

    private fun expireLeases() {
        transactions.executeWithoutResult {
            queries.lockExpiredLeases(clock.instant(), properties.batchSize).forEach { job ->
                log.warn("Job lease expired: jobId={} type={} attempt={}", job.id, job.type, job.attempt)
                recordFailure(job, JobOutcome.TemporaryFailure(LEASE_EXPIRED_REASON), AttemptOutcome.LEASE_EXPIRED)
            }
        }
    }

    /** Step 2: no transaction here, so no connection or lock is held while a handler waits on the ERP. */
    private fun work(job: ClaimedJob): JobOutcome {
        val handler = handlersByType[job.type] ?: return JobOutcome.PermanentFailure("No handler for job type '${job.type}'")
        return runHandler(job, handler)
    }

    private fun <T : Any> runHandler(
        job: ClaimedJob,
        handler: JobHandler<T>,
    ): JobOutcome {
        val payload =
            try {
                json.readValue(job.payloadJson, handler.payloadType)
            } catch (ex: JacksonException) {
                // The exception text can quote the payload, so it goes to the log only.
                log.warn("Job payload unreadable: jobId={} type={}", job.id, job.type, ex)
                return JobOutcome.PermanentFailure("Payload could not be read as ${handler.payloadType.simpleName}")
            }
        return try {
            handler.handle(payload, JobContext(job.id, job.attempt, job.attemptRowId))
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // Deliberately broad: any unexpected error in a handler is a failed attempt, never a dead
            // worker. Counted as temporary (ADR 0009), so a bug ends as DEAD/EXHAUSTED, not an endless loop.
            log.error("Job handler failed unexpectedly: jobId={} type={} attempt={}", job.id, job.type, job.attempt, ex)
            JobOutcome.TemporaryFailure("Unexpected error: ${ex.javaClass.simpleName}")
        }
    }

    /**
     * Step 3: the new status and the outcome's effects in one transaction, fenced by the claim's version.
     * If the effects fail, the whole transaction rolls back and the job stays RUNNING until its lease
     * expires: the same as a crash, and retried the same way.
     */
    private fun finish(
        job: ClaimedJob,
        outcome: JobOutcome,
    ) {
        try {
            transactions.executeWithoutResult {
                val owned =
                    when (outcome) {
                        is JobOutcome.Succeeded -> queries.markSucceeded(job, clock.instant())
                        is JobOutcome.TemporaryFailure -> recordFailure(job, outcome, AttemptOutcome.TEMPORARY_FAILURE)
                        is JobOutcome.PermanentFailure -> recordFailure(job, outcome, AttemptOutcome.PERMANENT_FAILURE)
                    }
                if (owned) {
                    outcome.effects.apply()
                    if (outcome is JobOutcome.Succeeded) {
                        log.info(
                            "Job succeeded: jobId={} type={} attempt={}",
                            job.id,
                            job.type,
                            job.attempt,
                        )
                    }
                } else {
                    // Our lease expired while the handler ran, and the job is someone else's now.
                    log.warn("Job no longer owned when finishing, result discarded: jobId={} type={}", job.id, job.type)
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // Deliberately broad, as in runHandler: one job's failed bookkeeping must not stop the batch.
            log.error("Job result could not be saved, will be retried when the lease expires: jobId={}", job.id, ex)
        }
    }

    /** @return false if the job is no longer ours, so nothing was recorded. */
    private fun recordFailure(
        job: ClaimedJob,
        outcome: JobOutcome,
        attemptOutcome: AttemptOutcome,
    ): Boolean {
        val now = clock.instant()
        val (reason, deadReason) =
            when (outcome) {
                is JobOutcome.PermanentFailure -> {
                    outcome.reason to DeadReason.PERMANENT
                }

                is JobOutcome.TemporaryFailure -> {
                    outcome.reason to (if (job.attempt >= job.maxAttempts) DeadReason.EXHAUSTED else null)
                }

                is JobOutcome.Succeeded -> {
                    error("Not a failure")
                }
            }
        val nextRunAt = if (deadReason == null) now.plus(properties.backoffAfter(job.attempt, random)) else now
        val recorded = queries.markFailed(job, now, attemptOutcome, reason.take(MAX_ERROR_LENGTH), deadReason, nextRunAt)
        when {
            !recorded -> Unit
            deadReason != null -> log.warn("Job dead: jobId={} type={} reason={} attempts={}", job.id, job.type, deadReason, job.attempt)
            else -> log.info("Job failed, will retry: jobId={} type={} attempt={} nextRunAt={}", job.id, job.type, job.attempt, nextRunAt)
        }
        return recorded
    }

    private companion object {
        const val LEASE_EXPIRED_REASON = "Lease expired: the worker did not finish in time"
        const val MAX_ERROR_LENGTH = 2000
    }
}
