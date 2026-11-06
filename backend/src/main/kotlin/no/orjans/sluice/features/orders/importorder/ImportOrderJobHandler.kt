package no.orjans.sluice.features.orders.importorder

import no.orjans.sluice.features.jobs.JobHandler
import no.orjans.sluice.features.jobs.JobOutcome
import no.orjans.sluice.features.jobs.JobType
import no.orjans.sluice.features.orders.ImportOrderPayload
import no.orjans.sluice.features.orders.OrderImport
import no.orjans.sluice.features.orders.OrderStatus
import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.Sku
import no.orjans.sluice.shared.atUtc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Imports a received order: checks that every line has a valid SKU and marks it `ACCEPTED`.
 * Milestone 2 adds the mapping and the call to the ERP here.
 *
 * Idempotent: an order that is already accepted succeeds again without changes.
 * A missing or invalid SKU is a permanent failure: retrying will not make the SKU appear.
 */
@Component
class ImportOrderJobHandler(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : JobHandler<ImportOrderPayload> {
    override val type: JobType = OrderImport.JOB_TYPE
    override val payloadType: Class<ImportOrderPayload> = ImportOrderPayload::class.java

    override fun handle(payload: ImportOrderPayload): JobOutcome {
        val status = currentStatus(payload)
        val invalidLines = if (status == OrderStatus.RECEIVED) linesWithInvalidSku(payload) else emptyList()
        return when {
            status == null -> {
                JobOutcome.PermanentFailure("Order ${payload.orderId} not found")
            }

            status == OrderStatus.ACCEPTED -> {
                JobOutcome.Succeeded
            }

            invalidLines.isNotEmpty() -> {
                JobOutcome.PermanentFailure("Missing or invalid SKU on line ${invalidLines.joinToString(", ")}")
            }

            else -> {
                accept(payload)
                JobOutcome.Succeeded
            }
        }
    }

    /** Locks the order row, so two runs of the same job can't both accept it. */
    private fun currentStatus(payload: ImportOrderPayload): OrderStatus? =
        jdbc
            .sql("SELECT status FROM orders WHERE id = :id FOR UPDATE")
            .param("id", payload.orderId)
            .query(String::class.java)
            .optional()
            .map(OrderStatus::valueOf)
            .orElse(null)

    private fun linesWithInvalidSku(payload: ImportOrderPayload): List<Int> =
        jdbc
            .sql("SELECT line_number, sku FROM order_lines WHERE order_id = :id ORDER BY line_number")
            .param("id", payload.orderId)
            .query { rs, _ -> rs.getInt("line_number") to rs.getString("sku") }
            .list()
            .filter { (_, sku) -> Sku.parse(sku) is Result.Failure }
            .map { (lineNumber, _) -> lineNumber }

    private fun accept(payload: ImportOrderPayload) {
        jdbc
            .sql(
                """
                UPDATE orders SET status = :accepted, updated_at = :now, version = version + 1
                WHERE id = :id AND status = :received
                """.trimIndent(),
            ).param("id", payload.orderId)
            .param("accepted", OrderStatus.ACCEPTED.name)
            .param("received", OrderStatus.RECEIVED.name)
            .param("now", clock.instant().atUtc())
            .update()
    }
}
