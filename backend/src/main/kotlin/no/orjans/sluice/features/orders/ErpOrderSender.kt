package no.orjans.sluice.features.orders

import no.orjans.sluice.shared.Money
import no.orjans.sluice.shared.Quantity
import no.orjans.sluice.shared.Sku
import java.time.Instant
import java.util.Currency

/**
 * Port to the ERP (ADR 0001): the import job depends on this interface, and the HTTP adapter lives
 * in `infrastructure/erp`. Another ERP is another implementation, without touching the job.
 *
 * Contract (ADR 0010): sending the same order again must not create a second ERP order. The adapter
 * sends [ErpOrder.orderReference] as the idempotency key, and the ERP returns the original result.
 * Implementations never throw for ERP or network problems; they return [ErpSendResult].
 */
interface ErpOrderSender {
    fun send(order: ErpOrder): ErpSendResult
}

/** The order in the ERP's terms: our own format, independent of Shopify. */
data class ErpOrder(
    /** Our order ID. Also the idempotency key. */
    val orderReference: OrderId,
    /** The number the customer sees, e.g. "#1042". */
    val externalOrderNumber: String,
    val currency: Currency,
    val orderedAt: Instant,
    val lines: List<ErpOrderLine>,
)

data class ErpOrderLine(
    val lineNumber: Int,
    val sku: Sku,
    val quantity: Quantity,
    val unitPrice: Money,
)

sealed interface ErpSendResult {
    /** The ERP has the order. Also the answer when the order was sent before (a replay). */
    data class Accepted(
        val erpOrderNumber: String,
        val lines: List<ErpLineConfirmation>,
    ) : ErpSendResult

    /** The ERP refused the order (4xx). Sending it again will not help: a permanent failure. */
    data class Rejected(
        val errors: List<ErpLineError>,
    ) : ErpSendResult

    /** No usable answer (timeout, connection error, 5xx). May work later: a temporary failure. */
    data class Unavailable(
        val reason: String,
    ) : ErpSendResult
}

data class ErpLineConfirmation(
    val lineNumber: Int,
    val erpLineId: String,
)

/** [lineNumber] is null for an error about the order as a whole. */
data class ErpLineError(
    val lineNumber: Int?,
    val code: String,
    val message: String,
)
