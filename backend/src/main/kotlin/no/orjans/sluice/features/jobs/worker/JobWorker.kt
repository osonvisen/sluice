package no.orjans.sluice.features.jobs.worker

import no.orjans.sluice.features.jobs.DeadReason
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

/**
 * Runs due jobs. Each call to [runOnce] is one poll:
 * 1. Expired leases (crashed workers) count as a failed attempt.
 * 2. Up to `batch-size` due jobs are claimed in one short transaction.
 * 3. Each job runs in its own transaction with its handler; the outcome decides the next status (ADR 0009).
 */
@Component
class JobWorker(
    handlers: List<JobHandler<*>>,
    private val queries: WorkerQueries,
    private val transactions: TransactionTemplate,
    private val json: JsonMapper,
    private val clock: Clock,
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
        claimed.forEach(::run)
        return claimed.size
    }

    private fun expireLeases() {
        transactions.executeWithoutResult {
            val now = clock.instant()
            queries.lockExpiredLeases(now, properties.batchSize).forEach { job ->
                log.warn("Job lease expired: jobId={} type={} attempt={}", job.id, job.type, job.attempt)
                recordFailure(job, JobOutcome.TemporaryFailure(LEASE_EXPIRED_REASON), AttemptOutcome.LEASE_EXPIRED)
            }
        }
    }

    private fun run(job: ClaimedJob) {
        val handler = handlersByType[job.type]
        val outcome =
            if (handler == null) {
                JobOutcome.PermanentFailure("No handler for job type '${job.type}'")
            } else {
                runHandler(job, handler)
            }
        when (outcome) {
            JobOutcome.Succeeded -> {
                log.info("Job succeeded: jobId={} type={} attempt={}", job.id, job.type, job.attempt)
            }

            is JobOutcome.TemporaryFailure -> {
                transactions.executeWithoutResult {
                    recordFailure(
                        job,
                        outcome,
                        AttemptOutcome.TEMPORARY_FAILURE,
                    )
                }
            }

            is JobOutcome.PermanentFailure -> {
                transactions.executeWithoutResult {
                    recordFailure(
                        job,
                        outcome,
                        AttemptOutcome.PERMANENT_FAILURE,
                    )
                }
            }
        }
    }

    /**
     * The handler's writes and `SUCCEEDED` commit together. Any other outcome rolls the handler's
     * writes back; the failure is then recorded in a new transaction.
     */
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
            transactions.execute { tx ->
                val outcome = handler.handle(payload)
                when {
                    outcome != JobOutcome.Succeeded -> {
                        tx.setRollbackOnly()
                        outcome
                    }

                    queries.markSucceeded(job, clock.instant()) -> {
                        outcome
                    }

                    else -> {
                        // Our lease expired while the handler ran, and the job is someone else's now.
                        tx.setRollbackOnly()
                        log.warn("Job lost its lease before finishing, discarding result: jobId={}", job.id)
                        JobOutcome.TemporaryFailure(LEASE_EXPIRED_REASON)
                    }
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // Deliberately broad: any unexpected error in a handler is a failed attempt, never a dead
            // worker. Counted as temporary (ADR 0009), so a bug ends as DEAD/EXHAUSTED, not an endless loop.
            log.error("Job handler failed unexpectedly: jobId={} type={} attempt={}", job.id, job.type, job.attempt, ex)
            JobOutcome.TemporaryFailure("Unexpected error: ${ex.javaClass.simpleName}")
        }
    }

    private fun recordFailure(
        job: ClaimedJob,
        outcome: JobOutcome,
        attemptOutcome: AttemptOutcome,
    ) {
        val now = clock.instant()
        val (reason, deadReason) =
            when (outcome) {
                is JobOutcome.PermanentFailure -> {
                    outcome.reason to DeadReason.PERMANENT
                }

                is JobOutcome.TemporaryFailure -> {
                    outcome.reason to (if (job.attempt >= job.maxAttempts) DeadReason.EXHAUSTED else null)
                }

                JobOutcome.Succeeded -> {
                    error("Not a failure")
                }
            }
        val nextRunAt = if (deadReason == null) now.plus(properties.backoffAfter(job.attempt)) else now
        val recorded = queries.markFailed(job, now, attemptOutcome, reason.take(MAX_ERROR_LENGTH), deadReason, nextRunAt)
        if (!recorded) {
            log.warn("Job changed while running, failure not recorded: jobId={}", job.id)
        } else if (deadReason != null) {
            log.warn("Job dead: jobId={} type={} reason={} attempts={}", job.id, job.type, deadReason, job.attempt)
        } else {
            log.info("Job failed, will retry: jobId={} type={} attempt={} nextRunAt={}", job.id, job.type, job.attempt, nextRunAt)
        }
    }

    private companion object {
        const val LEASE_EXPIRED_REASON = "Lease expired: the worker did not finish in time"
        const val MAX_ERROR_LENGTH = 2000
    }
}
