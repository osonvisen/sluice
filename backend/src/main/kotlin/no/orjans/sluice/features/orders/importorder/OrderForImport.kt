package no.orjans.sluice.features.orders.importorder

import no.orjans.sluice.features.orders.OrderId
import no.orjans.sluice.features.orders.OrderStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The order as stored, before it is checked and mapped to the ERP's format. */
data class OrderForImport(
    val id: OrderId,
    val orderNumber: String,
    val currencyCode: String,
    val orderedAt: Instant,
    val status: OrderStatus,
    val lines: List<StoredLine>,
)

data class StoredLine(
    val lineNumber: Int,
    val sku: String?,
    val quantity: Int,
    val unitPrice: BigDecimal,
)

/** Reads only: the import job must not write before the worker's finish step (ADR 0010). */
@Component
class OrderForImportReader(
    private val jdbc: JdbcClient,
) {
    fun find(id: UUID): OrderForImport? {
        val lines =
            jdbc
                .sql("SELECT line_number, sku, quantity, unit_price FROM order_lines WHERE order_id = :id ORDER BY line_number")
                .param("id", id)
                .query {
                    rs,
                    _,
                    ->
                    StoredLine(rs.getInt("line_number"), rs.getString("sku"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"))
                }.list()
        return jdbc
            .sql("SELECT id, order_number, currency, ordered_at, status FROM orders WHERE id = :id")
            .param("id", id)
            .query { rs, _ ->
                OrderForImport(
                    id = OrderId.of(rs.getObject("id", UUID::class.java)),
                    orderNumber = rs.getString("order_number"),
                    currencyCode = rs.getString("currency"),
                    orderedAt = rs.getObject("ordered_at", OffsetDateTime::class.java).toInstant(),
                    status = OrderStatus.valueOf(rs.getString("status")),
                    lines = lines,
                )
            }.optional()
            .orElse(null)
    }
}
