package no.orjans.sluice.features.jobs.getjob

import no.orjans.sluice.features.jobs.AttemptOutcome
import no.orjans.sluice.features.jobs.DeadReason
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobStatus
import no.orjans.sluice.features.orders.LineOutcome
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.Result
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * One job with everything an operator needs to understand it: what it works on (payload), every attempt,
 * and what was sent and received per order line. Three queries, whatever the size of the history (no N+1),
 * using `job_attempts_job_ix` and `job_line_log_attempt_ix`.
 *
 * The reads are not in one transaction: a worker finishing in between can make the history one attempt
 * newer than the job row. The ETag is the job row's version, so "Run again" still checks the right state.
 */
@Component
class GetJobHandler(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    fun handle(id: JobId): Result<VersionedJob> {
        val job =
            jdbc
                .sql(
                    """
                    SELECT j.id, j.type, j.status, j.dead_reason, j.attempts, j.max_attempts, j.next_run_at, j.last_error,
                           j.payload::text AS payload, j.created_at, j.updated_at, j.version, o.order_number
                    FROM jobs j LEFT JOIN orders o ON o.import_job_id = j.id
                    WHERE j.id = :id
                    """.trimIndent(),
                ).param("id", id.value)
                .query { rs, _ -> rs.version() to rs.toResponse() }
                .optional()
                .orElse(null) ?: return Result.failure(AppError.NotFound("job", id.toString()))

        val (version, response) = job
        return Result.success(VersionedJob(version, response.copy(attemptHistory = attempts(id), lineLog = lineLog(id))))
    }

    private fun attempts(id: JobId): List<JobAttemptResponse> =
        jdbc
            .sql(
                """
                SELECT $RUN_NUMBER AS run, attempt, started_at, finished_at, outcome, error
                FROM job_attempts WHERE job_id = :id ORDER BY id
                """.trimIndent(),
            ).param("id", id.value)
            .query { rs, _ ->
                JobAttemptResponse(
                    run = rs.getInt("run"),
                    attempt = rs.getInt("attempt"),
                    startedAt = rs.instant("started_at"),
                    finishedAt = rs.instantOrNull("finished_at"),
                    outcome = rs.getString("outcome")?.let(AttemptOutcome::valueOf),
                    error = rs.getString("error"),
                )
            }.list()

    private fun lineLog(id: JobId): List<JobLineLogEntry> =
        jdbc
            .sql(
                """
                WITH attempts AS (
                    SELECT id, attempt, $RUN_NUMBER AS run FROM job_attempts WHERE job_id = :id
                )
                SELECT a.run, a.attempt, l.line_number, l.outcome, l.sent::text AS sent, l.received::text AS received,
                       l.error_code, l.error_message, l.logged_at
                FROM job_line_log l JOIN attempts a ON a.id = l.job_attempt_id
                ORDER BY a.id, l.line_number, l.id
                """.trimIndent(),
            ).param("id", id.value)
            .query { rs, _ ->
                JobLineLogEntry(
                    run = rs.getInt("run"),
                    attempt = rs.getInt("attempt"),
                    lineNumber = rs.getInt("line_number"),
                    outcome = LineOutcome.valueOf(rs.getString("outcome")),
                    sent = rs.getString("sent")?.let(::jsonObject),
                    received = rs.getString("received")?.let(::jsonObject),
                    errorCode = rs.getString("error_code"),
                    errorMessage = rs.getString("error_message"),
                    loggedAt = rs.instant("logged_at"),
                )
            }.list()

    private fun ResultSet.version() = getLong("version")

    private fun ResultSet.toResponse() =
        GetJobResponse(
            id = getString("id"),
            type = getString("type"),
            status = JobStatus.valueOf(getString("status")),
            deadReason = getString("dead_reason")?.let(DeadReason::valueOf),
            attempts = getInt("attempts"),
            maxAttempts = getInt("max_attempts"),
            nextRunAt = instant("next_run_at"),
            lastError = getString("last_error"),
            orderNumber = getString("order_number"),
            payload = jsonObject(getString("payload")),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
            attemptHistory = emptyList(),
            lineLog = emptyList(),
        )

    private fun jsonObject(text: String): Map<String, Any?> = json.readValue(text, JSON_OBJECT)

    private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

    private fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private companion object {
        /**
         * Which run an attempt belongs to: 1 for the job's first run, +1 for each manual "Run again", which
         * starts counting attempts from 1 again. Without it, the history after two reruns reads "1, 1, 1".
         */
        const val RUN_NUMBER = "count(*) FILTER (WHERE attempt = 1) OVER (ORDER BY id)"
        val JSON_OBJECT = object : TypeReference<Map<String, Any?>>() {}
    }
}

data class VersionedJob(
    val version: Long,
    val response: GetJobResponse,
)

/** Times are ISO 8601 in UTC; the frontend shows them in the user's language and time zone. */
data class GetJobResponse(
    val id: String,
    val type: String,
    val status: JobStatus,
    val deadReason: DeadReason?,
    /** Attempts in the current run; a manual "Run again" starts again from 0. */
    val attempts: Int,
    val maxAttempts: Int,
    /** When the next attempt is due (`PENDING`, `FAILED`); for other statuses the time it was last set. */
    val nextRunAt: Instant,
    val lastError: String?,
    /** The order an `order.import` job imports; null for other job types. */
    val orderNumber: String?,
    /** What the job works on, as stored. By the queue's rule it holds IDs only, never personal data (`JobQueue`). */
    val payload: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Every attempt, oldest first, across manual reruns. */
    val attemptHistory: List<JobAttemptResponse>,
    /** What was sent and received per order line, per attempt. Empty for job types without lines. */
    val lineLog: List<JobLineLogEntry>,
)

data class JobAttemptResponse(
    /** 1 for the first run, 2 after the first manual "Run again", and so on. */
    val run: Int,
    /** The attempt within its run (1 to `maxAttempts`). */
    val attempt: Int,
    val startedAt: Instant,
    /** Null while the attempt is running. */
    val finishedAt: Instant?,
    val outcome: AttemptOutcome?,
    val error: String?,
)

data class JobLineLogEntry(
    /** The run and attempt the line was logged in, as in [JobAttemptResponse]. */
    val run: Int,
    val attempt: Int,
    val lineNumber: Int,
    val outcome: LineOutcome,
    /** The line as sent to the ERP; null if it was stopped locally. */
    val sent: Map<String, Any?>?,
    /** The ERP's answer for the line; null if there was none. */
    val received: Map<String, Any?>?,
    val errorCode: String?,
    val errorMessage: String?,
    val loggedAt: Instant,
)
