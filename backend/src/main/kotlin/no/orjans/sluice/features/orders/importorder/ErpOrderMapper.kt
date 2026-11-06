package no.orjans.sluice.features.orders.importorder

import no.orjans.sluice.features.orders.ErpOrder
import no.orjans.sluice.features.orders.ErpOrderLine
import no.orjans.sluice.shared.Money
import no.orjans.sluice.shared.Quantity
import no.orjans.sluice.shared.Sku
import no.orjans.sluice.shared.valueOrNull
import org.springframework.stereotype.Component
import java.util.Currency

sealed interface ErpMapping {
    data class Ready(
        val order: ErpOrder,
    ) : ErpMapping

    /** At least one line can't be sent. Nothing is sent: the ERP gets whole orders or nothing. */
    data class Invalid(
        val problems: List<LineProblem>,
    ) : ErpMapping
}

data class LineProblem(
    val lineNumber: Int,
    val code: String,
    val message: String,
)

/** Maps a stored order to the ERP's format, checking every line. Pure: no I/O, easy to unit test. */
@Component
class ErpOrderMapper {
    fun map(order: OrderForImport): ErpMapping {
        val currency = Currency.getInstance(order.currencyCode)
        val problems = mutableListOf<LineProblem>()
        val lines =
            order.lines.mapNotNull { line ->
                val sku = Sku.parse(line.sku).valueOrNull()
                val quantity = Quantity.of(line.quantity).valueOrNull()
                val price = Money.of(line.unitPrice, currency.currencyCode).valueOrNull()
                when {
                    sku == null -> problems += problem(line, if (line.sku.isNullOrBlank()) MISSING_SKU else INVALID_SKU)
                    quantity == null -> problems += problem(line, INVALID_QUANTITY)
                    price == null -> problems += problem(line, INVALID_PRICE)
                }
                if (sku != null && quantity != null && price != null) ErpOrderLine(line.lineNumber, sku, quantity, price) else null
            }
        return when {
            order.lines.isEmpty() -> ErpMapping.Invalid(emptyList())
            problems.isNotEmpty() -> ErpMapping.Invalid(problems)
            else -> ErpMapping.Ready(ErpOrder(order.id, order.orderNumber, currency, order.orderedAt, lines))
        }
    }

    private fun problem(
        line: StoredLine,
        code: String,
    ) = LineProblem(line.lineNumber, code, MESSAGES.getValue(code))

    companion object {
        const val MISSING_SKU = "MISSING_SKU"
        const val INVALID_SKU = "INVALID_SKU"
        const val INVALID_QUANTITY = "INVALID_QUANTITY"
        const val INVALID_PRICE = "INVALID_PRICE"

        // For operators, in the job's error and the line log; never contains order data.
        private val MESSAGES =
            mapOf(
                MISSING_SKU to "The line has no SKU",
                INVALID_SKU to "The SKU is not valid",
                INVALID_QUANTITY to "The quantity must be at least 1",
                INVALID_PRICE to "The price is not valid for the order's currency",
            )
    }
}
