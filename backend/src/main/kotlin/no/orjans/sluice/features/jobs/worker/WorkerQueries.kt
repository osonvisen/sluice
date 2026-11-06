package no.orjans.sluice.features.jobs.worker

import no.orjans.sluice.features.jobs.DeadReason
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobStatus
import no.orjans.sluice.features.jobs.JobType
import no.orjans.sluice.shared.atUtc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A job a worker holds the lease on. [version] is the version after claiming. */
data class ClaimedJob(
    val id: JobId,
    val type: JobType,
    val payloadJson: String,
    val attempt: Int,
    val maxAttempts: Int,
    val version: Long,
    val attemptRowId: Long,
)

/** How an attempt ended, as stored in `job_attempts.outcome`. */
enum class AttemptOutcome { SUCCEEDED, TEMPORARY_FAILURE, PERMANENT_FAILURE, LEASE_EXPIRED }

/** The worker's SQL. Every state change checks `status = 'RUNNING'` and the version, so a worker that lost its lease changes nothing. */
@Component
class WorkerQueries(
    private val jdbc: JdbcClient,
) {
    /**
     * Claims up to [limit] due jobs. `FOR UPDATE SKIP LOCKED` lets several workers run this at the
     * same time: each skips rows another worker has locked, so no job is claimed twice.
     */
    fun claimDue(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ClaimedJob> {
        val claimed =
            jdbc
                .sql(
                    """
                    UPDATE jobs
                    SET status = 'RUNNING', attempts = attempts + 1, locked_until = :leaseUntil,
                        updated_at = :now, version = version + 1
                    WHERE id IN (
                        SELECT id FROM jobs
                        WHERE status IN ('PENDING', 'FAILED') AND next_run_at <= :now
                        ORDER BY next_run_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, type, payload::text AS payload, attempts, max_attempts, version
                    """.trimIndent(),
                ).param("now", now.atUtc())
                .param("leaseUntil", leaseUntil.atUtc())
                .param("limit", limit)
                .query { rs, _ -> rs.toClaimedJob() }
                .list()
        return claimed.map { it.copy(attemptRowId = startAttempt(it.id, it.attempt, now)) }
    }

    /** RUNNING jobs whose worker did not finish in time (crash, kill, very slow handler). Locked so two workers don't both expire one. */
    fun lockExpiredLeases(
        now: Instant,
        limit: Int,
    ): List<ClaimedJob> =
        jdbc
            .sql(
                """
                SELECT j.id, j.type, j.payload::text AS payload, j.attempts, j.max_attempts, j.version,
                       (SELECT max(a.id) FROM job_attempts a WHERE a.job_id = j.id) AS attempt_row_id
                FROM jobs j
                WHERE j.status = 'RUNNING' AND j.locked_until < :now
                ORDER BY j.locked_until
                LIMIT :limit
                FOR UPDATE OF j SKIP LOCKED
                """.trimIndent(),
            ).param("now", now.atUtc())
            .param("limit", limit)
            .query { rs, _ -> rs.toClaimedJob().copy(attemptRowId = rs.getLong("attempt_row_id")) }
            .list()

    /** @return false if the job is no longer ours (lease expired and someone else took it). */
    fun markSucceeded(
        job: ClaimedJob,
        now: Instant,
    ): Boolean {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE jobs SET status = 'SUCCEEDED', locked_until = NULL, last_error = NULL,
                                    updated_at = :now, version = version + 1
                    WHERE id = :id AND status = 'RUNNING' AND version = :version
                    """.trimIndent(),
                ).param("id", job.id.value)
                .param("version", job.version)
                .param("now", now.atUtc())
                .update()
        if (updated == 1) finishAttempt(job.attemptRowId, now, AttemptOutcome.SUCCEEDED, error = null)
        return updated == 1
    }

    /** Moves the job to `FAILED` (retry at [nextRunAt]) or `DEAD` (when [deadReason] is set). */
    fun markFailed(
        job: ClaimedJob,
        now: Instant,
        outcome: AttemptOutcome,
        error: String,
        deadReason: DeadReason?,
        nextRunAt: Instant,
    ): Boolean {
        val status = if (deadReason == null) JobStatus.FAILED else JobStatus.DEAD
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE jobs SET status = :status, dead_reason = :deadReason, locked_until = NULL,
                                    last_error = :error, next_run_at = :nextRunAt, updated_at = :now, version = version + 1
                    WHERE id = :id AND status = 'RUNNING' AND version = :version
                    """.trimIndent(),
                ).param("id", job.id.value)
                .param("version", job.version)
                .param("status", status.name)
                .param("deadReason", deadReason?.name)
                .param("error", error)
                .param("nextRunAt", nextRunAt.atUtc())
                .param("now", now.atUtc())
                .update()
        if (updated == 1) finishAttempt(job.attemptRowId, now, outcome, error)
        return updated == 1
    }

    private fun startAttempt(
        jobId: JobId,
        attempt: Int,
        now: Instant,
    ): Long =
        jdbc
            .sql("INSERT INTO job_attempts (job_id, attempt, started_at) VALUES (:jobId, :attempt, :now) RETURNING id")
            .param("jobId", jobId.value)
            .param("attempt", attempt)
            .param("now", now.atUtc())
            .query(Long::class.java)
            .single()

    private fun finishAttempt(
        attemptRowId: Long,
        now: Instant,
        outcome: AttemptOutcome,
        error: String?,
    ) {
        jdbc
            .sql("UPDATE job_attempts SET finished_at = :now, outcome = :outcome, error = :error WHERE id = :id")
            .param("id", attemptRowId)
            .param("now", now.atUtc())
            .param("outcome", outcome.name)
            .param("error", error)
            .update()
    }

    private fun ResultSet.toClaimedJob() =
        ClaimedJob(
            id = JobId.of(getObject("id", UUID::class.java)),
            type = JobType(getString("type")),
            payloadJson = getString("payload"),
            attempt = getInt("attempts"),
            maxAttempts = getInt("max_attempts"),
            version = getLong("version"),
            attemptRowId = NOT_STARTED,
        )

    private companion object {
        const val NOT_STARTED = -1L
    }
}
