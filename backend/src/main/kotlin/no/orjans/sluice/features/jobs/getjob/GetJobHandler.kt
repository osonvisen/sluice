package no.orjans.sluice.features.jobs.getjob

import no.orjans.sluice.features.jobs.DeadReason
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobStatus
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.Result
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime

@Component
class GetJobHandler(
    private val jdbc: JdbcClient,
) {
    fun handle(id: JobId): Result<VersionedJob> {
        val job =
            jdbc
                .sql(
                    """
                    SELECT id, type, status, dead_reason, attempts, max_attempts, next_run_at, last_error,
                           created_at, updated_at, version
                    FROM jobs WHERE id = :id
                    """.trimIndent(),
                ).param("id", id.value)
                .query { rs, _ ->
                    VersionedJob(
                        version = rs.getLong("version"),
                        response =
                            GetJobResponse(
                                id = rs.getString("id"),
                                type = rs.getString("type"),
                                status = JobStatus.valueOf(rs.getString("status")),
                                deadReason = rs.getString("dead_reason")?.let(DeadReason::valueOf),
                                attempts = rs.getInt("attempts"),
                                maxAttempts = rs.getInt("max_attempts"),
                                nextRunAt = rs.instant("next_run_at"),
                                lastError = rs.getString("last_error"),
                                createdAt = rs.instant("created_at"),
                                updatedAt = rs.instant("updated_at"),
                            ),
                    )
                }.optional()
        return job.map { Result.success(it) }.orElse(Result.failure(AppError.NotFound("job", id.toString())))
    }

    private fun java.sql.ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
}

data class VersionedJob(
    val version: Long,
    val response: GetJobResponse,
)

/** Times are ISO 8601 in UTC; the frontend shows them in the user's time zone. */
data class GetJobResponse(
    val id: String,
    val type: String,
    val status: JobStatus,
    val deadReason: DeadReason?,
    val attempts: Int,
    val maxAttempts: Int,
    val nextRunAt: Instant,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
