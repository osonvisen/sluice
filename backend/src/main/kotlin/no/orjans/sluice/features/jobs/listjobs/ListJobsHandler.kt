package no.orjans.sluice.features.jobs.listjobs

import no.orjans.sluice.features.jobs.DeadReason
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobStatus
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import no.orjans.sluice.shared.Page
import no.orjans.sluice.shared.PageRequest
import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.map
import no.orjans.sluice.shared.valueOrNull
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * One count and one page query, both with the same filter. The filter is built from fixed SQL fragments
 * and bound parameters only, so no user input ever becomes SQL.
 *
 * Indexes: `jobs_status_created_ix` with a status, `jobs_created_ix` without; search uses the primary key
 * (job ID) or `orders_order_number_ix`. The order number comes from the order the job imports
 * (`orders.import_job_id`); other job types simply have none.
 */
@Component
class ListJobsHandler(
    private val jdbc: JdbcClient,
) {
    fun handle(query: JobListQuery): Page<JobListItem> {
        val filter = Filter.of(query)
        val total =
            filter
                .bind(jdbc.sql("SELECT count(*) FROM jobs j LEFT JOIN orders o ON o.import_job_id = j.id ${filter.where}"))
                .query(Long::class.java)
                .single()
        if (total == 0L || query.page.offset >= total) return Page.of(query.page, emptyList(), total)

        val items =
            filter
                .bind(
                    jdbc.sql(
                        """
                        SELECT j.id, j.type, j.status, j.dead_reason, j.attempts, j.max_attempts, j.next_run_at,
                               j.last_error, j.created_at, j.updated_at, o.order_number
                        FROM jobs j LEFT JOIN orders o ON o.import_job_id = j.id
                        ${filter.where}
                        ORDER BY j.created_at DESC, j.id DESC
                        LIMIT :limit OFFSET :offset
                        """.trimIndent(),
                    ),
                ).param("limit", query.page.size)
                .param("offset", query.page.offset)
                .query { rs, _ -> rs.toItem() }
                .list()
        return Page.of(query.page, items, total)
    }

    private class Filter(
        val where: String,
        private val params: Map<String, Any>,
    ) {
        fun bind(statement: JdbcClient.StatementSpec): JdbcClient.StatementSpec = statement.params(params)

        companion object {
            fun of(query: JobListQuery): Filter {
                val conditions = mutableListOf<String>()
                val params = mutableMapOf<String, Any>()
                query.status?.let {
                    conditions += "j.status = :status"
                    params["status"] = it.name
                }
                when (val search = query.search) {
                    is Search.ByJobId -> {
                        conditions += "j.id = :jobId"
                        params["jobId"] = search.id.value
                    }

                    is Search.ByOrderNumber -> {
                        conditions += "o.order_number IN (:orderNumbers)"
                        params["orderNumbers"] = search.candidates
                    }

                    null -> {
                        Unit
                    }
                }
                val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
                return Filter(where, params)
            }
        }
    }

    private fun ResultSet.toItem() =
        JobListItem(
            id = getString("id"),
            type = getString("type"),
            status = JobStatus.valueOf(getString("status")),
            deadReason = getString("dead_reason")?.let(DeadReason::valueOf),
            attempts = getInt("attempts"),
            maxAttempts = getInt("max_attempts"),
            nextRunAt = instant("next_run_at"),
            lastError = getString("last_error"),
            orderNumber = getString("order_number"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )

    private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
}

/** A validated list request. Built only through [of], so the handler never sees an out-of-range page. */
@ConsistentCopyVisibility
data class JobListQuery private constructor(
    val status: JobStatus?,
    val search: Search?,
    val page: PageRequest,
) {
    companion object {
        const val MAX_SEARCH_LENGTH = 100
        private val TOO_LONG = ErrorCode("size")

        fun of(
            status: JobStatus?,
            q: String?,
            page: Int,
            size: Int,
        ): Result<JobListQuery> {
            val text = q?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null && text.length > MAX_SEARCH_LENGTH) {
                return Result.failure(AppError.Validation(listOf(FieldViolation("q", TOO_LONG))))
            }
            return PageRequest.of(page, size).map { JobListQuery(status, text?.let(Search::of), it) }
        }
    }
}

/** What `q` means: a job ID if it is one, otherwise an order number. */
sealed interface Search {
    data class ByJobId(
        val id: JobId,
    ) : Search

    /** Shopify order numbers look like `#1042`; operators type `1042` just as often, so both are tried. */
    data class ByOrderNumber(
        val candidates: List<String>,
    ) : Search

    companion object {
        fun of(text: String): Search =
            JobId.parse(text).map<JobId, Search>(::ByJobId).valueOrNull()
                ?: text.removePrefix("#").let { ByOrderNumber(listOf(it, "#$it")) }
    }
}

/** A row in the overview. Times are ISO 8601 in UTC; the frontend shows them in the user's language and time zone. */
data class JobListItem(
    val id: String,
    val type: String,
    val status: JobStatus,
    val deadReason: DeadReason?,
    val attempts: Int,
    val maxAttempts: Int,
    val nextRunAt: Instant,
    /** Written for operators, never contains personal data (see `JobOutcome`). */
    val lastError: String?,
    /** The order an `order.import` job imports; null for other job types. */
    val orderNumber: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
