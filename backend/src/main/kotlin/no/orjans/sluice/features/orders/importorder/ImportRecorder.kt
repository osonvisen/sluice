package no.orjans.sluice.features.orders.importorder

import no.orjans.sluice.features.jobs.JobContext
import no.orjans.sluice.features.orders.ErpOrderLine
import no.orjans.sluice.features.orders.OrderId
import no.orjans.sluice.features.orders.OrderStatus
import no.orjans.sluice.shared.atUtc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** How one order line fared in one attempt. Stored as text; the database has a matching check constraint. */
enum class LineOutcome { ACCEPTED, REJECTED, ORDER_REJECTED, INVALID, NO_RESPONSE }

data class LineLogEntry(
    val lineNumber: Int,
    val outcome: LineOutcome,
    val sent: ErpOrderLine? = null,
    val received: Any? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/**
 * The import job's writes. Only called from a [no.orjans.sluice.features.jobs.JobEffects], i.e. in
 * the worker's finish transaction after it confirmed it still owns the job (ADR 0010).
 */
@Component
class ImportRecorder(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun logLines(
        orderId: OrderId,
        context: JobContext,
        entries: List<LineLogEntry>,
    ) {
        val now = clock.instant().atUtc()
        entries.forEach { entry ->
            jdbc
                .sql(
                    """
                    INSERT INTO job_line_log (job_attempt_id, order_id, line_number, outcome, sent, received,
                                              error_code, error_message, logged_at)
                    VALUES (:attemptId, :orderId, :lineNumber, :outcome, :sent::jsonb, :received::jsonb,
                            :errorCode, :errorMessage, :now)
                    """.trimIndent(),
                ).param("attemptId", context.attemptId)
                .param("orderId", orderId.value)
                .param("lineNumber", entry.lineNumber)
                .param("outcome", entry.outcome.name)
                .param("sent", entry.sent?.let { json.writeValueAsString(it.toLogJson()) })
                .param("received", entry.received?.let(json::writeValueAsString))
                .param("errorCode", entry.errorCode)
                .param("errorMessage", entry.errorMessage)
                .param("now", now)
                .update()
        }
    }

    fun markImported(
        orderId: OrderId,
        erpOrderNumber: String,
    ) {
        jdbc
            .sql(
                """
                UPDATE orders SET status = :imported, erp_order_number = :erpOrderNumber, imported_at = :now,
                                  updated_at = :now, version = version + 1
                WHERE id = :id AND status <> :imported
                """.trimIndent(),
            ).param("id", orderId.value)
            .param("imported", OrderStatus.IMPORTED.name)
            .param("erpOrderNumber", erpOrderNumber)
            .param("now", clock.instant().atUtc())
            .update()
    }

    fun markRejected(orderId: OrderId) {
        jdbc
            .sql(
                """
                UPDATE orders SET status = :rejected, updated_at = :now, version = version + 1
                WHERE id = :id AND status <> :imported
                """.trimIndent(),
            ).param("id", orderId.value)
            .param("rejected", OrderStatus.REJECTED.name)
            .param("imported", OrderStatus.IMPORTED.name)
            .param("now", clock.instant().atUtc())
            .update()
    }

    // Plain values in the log, so it reads the same however the value objects are serialised.
    private fun ErpOrderLine.toLogJson() =
        mapOf(
            "lineNumber" to lineNumber,
            "sku" to sku.value,
            "quantity" to quantity.value,
            "unitPrice" to unitPrice.amount,
            "currency" to unitPrice.currency.currencyCode,
        )
}
