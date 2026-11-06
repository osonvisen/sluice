package no.orjans.sluice.features.jobs.retryjob

import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobStatus
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.atUtc
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime

@Component
class RetryJobHandler(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One conditional UPDATE does the work: it only matches if the version is the client's and the
     * job is retryable. Two concurrent retries can therefore never both succeed. If nothing was
     * updated, a second read says why (404, 412 or 409).
     */
    @Transactional
    fun handle(
        id: JobId,
        expectedVersion: Long,
    ): Result<RetriedJob> {
        val now = clock.instant()
        val retried =
            jdbc
                .sql(
                    """
                    UPDATE jobs
                    SET status = 'PENDING', attempts = 0, dead_reason = NULL, next_run_at = :now,
                        updated_at = :now, version = version + 1
                    WHERE id = :id AND version = :version AND status IN (:retryable)
                    RETURNING id, status, attempts, next_run_at, version
                    """.trimIndent(),
                ).param("id", id.value)
                .param("version", expectedVersion)
                .param("retryable", JobStatus.RETRYABLE.map { it.name })
                .param("now", now.atUtc())
                .query { rs, _ ->
                    RetriedJob(
                        version = rs.getLong("version"),
                        response =
                            RetryJobResponse(
                                id = rs.getString("id"),
                                status = JobStatus.valueOf(rs.getString("status")),
                                attempts = rs.getInt("attempts"),
                                nextRunAt = rs.getObject("next_run_at", OffsetDateTime::class.java).toInstant(),
                            ),
                    )
                }.optional()
        if (retried.isPresent) {
            log.info("Job retried manually: jobId={}", id)
            return Result.success(retried.get())
        }
        return Result.failure(whyNotRetried(id, expectedVersion))
    }

    private fun whyNotRetried(
        id: JobId,
        expectedVersion: Long,
    ): AppError {
        val current =
            jdbc
                .sql("SELECT version FROM jobs WHERE id = :id")
                .param("id", id.value)
                .query(Long::class.java)
                .optional()
        return when {
            current.isEmpty -> AppError.NotFound("job", id.toString())
            current.get() != expectedVersion -> AppError.PreconditionFailed("job", id.toString())
            else -> AppError.Conflict(JOB_NOT_RETRYABLE)
        }
    }

    private companion object {
        val JOB_NOT_RETRYABLE = ErrorCode("job-not-retryable")
    }
}

data class RetriedJob(
    val version: Long,
    val response: RetryJobResponse,
)

data class RetryJobResponse(
    val id: String,
    val status: JobStatus,
    val attempts: Int,
    val nextRunAt: Instant,
)
