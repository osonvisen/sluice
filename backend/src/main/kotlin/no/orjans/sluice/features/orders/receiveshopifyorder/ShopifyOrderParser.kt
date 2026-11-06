package no.orjans.sluice.features.orders.receiveshopifyorder

import com.fasterxml.jackson.annotation.JsonProperty
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import no.orjans.sluice.shared.Money
import no.orjans.sluice.shared.Quantity
import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.valueOrNull
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Currency

/** An order as Sluice needs it: no customer data (data minimisation), money as [Money], quantities as [Quantity]. */
data class IncomingOrder(
    val externalOrderId: Long,
    val orderNumber: String,
    val currency: Currency,
    val orderedAt: Instant,
    val lines: List<IncomingOrderLine>,
)

data class IncomingOrderLine(
    val externalLineId: Long,
    /** As received. Checked by the import job, so an order with a missing SKU is stored and visible, not lost. */
    val sku: String?,
    val title: String,
    val quantity: Quantity,
    val unitPrice: Money,
)

/**
 * Reads the Shopify `orders/create` payload into an [IncomingOrder]. Only the fields we use are
 * mapped; customer, addresses and everything else are ignored and never stored.
 * Collects every problem at once, so one 400 lists all of them.
 */
@Component
class ShopifyOrderParser(
    private val json: JsonMapper,
) {
    fun parse(body: ByteArray): Result<IncomingOrder> {
        val payload =
            try {
                json.readValue(body, ShopifyOrderJson::class.java)
            } catch (_: JacksonException) {
                // The exception text may quote the payload (personal data), so it is not passed on.
                return invalid(FieldViolation("body", INVALID_JSON))
            }
        return toIncomingOrder(payload)
    }

    private fun toIncomingOrder(payload: ShopifyOrderJson): Result<IncomingOrder> {
        val checks = Checks()
        val id = checks.require("id", payload.id)
        val name = checks.require("name", payload.name?.takeIf { it.isNotBlank() })
        val currency = checks.require("currency", payload.currency?.let(::currencyOrNull), Money.INVALID_CURRENCY)
        val orderedAt = checks.require("created_at", parseInstant(payload.createdAt), INVALID_DATE)
        val items = checks.require("line_items", payload.lineItems?.takeIf { it.isNotEmpty() })
        val lines = items.orEmpty().mapIndexedNotNull { index, item -> toLine(item, "line_items[$index]", currency, checks) }

        if (checks.failed) return Result.failure(AppError.Validation(checks.violations))
        return Result.success(IncomingOrder(id.checked(), name.checked(), currency.checked(), orderedAt.checked(), lines))
    }

    /** Null if the line has a problem; the problem is recorded in [checks]. */
    private fun toLine(
        item: ShopifyLineItemJson,
        path: String,
        currency: Currency?,
        checks: Checks,
    ): IncomingOrderLine? {
        val before = checks.violations.size
        val lineId = checks.require("$path.id", item.id)
        val title = checks.require("$path.title", item.title?.takeIf { it.isNotBlank() })
        val quantity = checks.require("$path.quantity", item.quantity?.let { Quantity.of(it).valueOrNull() }, Quantity.INVALID_QUANTITY)
        // Shopify sends prices as strings ("199.00") precisely so they are never floats.
        val price = currency?.let { checks.require("$path.price", priceOrNull(item.price, it), Money.INVALID_AMOUNT) }
        if (currency == null || checks.violations.size > before) return null
        return IncomingOrderLine(lineId.checked(), item.sku, title.checked(), quantity.checked(), price.checked())
    }

    private fun priceOrNull(
        raw: String?,
        currency: Currency,
    ): Money? = raw?.toBigDecimalOrNull()?.let { Money.of(it, currency.currencyCode).valueOrNull() }

    private fun currencyOrNull(code: String): Currency? =
        try {
            Currency.getInstance(code)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseInstant(value: String?): Instant? =
        try {
            value?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (_: DateTimeParseException) {
            null
        }

    private fun invalid(vararg violations: FieldViolation) = Result.failure(AppError.Validation(violations.toList()))

    /** Collects every violation, so one 400 lists all problems instead of the first one. */
    private class Checks {
        val violations = mutableListOf<FieldViolation>()
        val failed: Boolean get() = violations.isNotEmpty()

        fun <T : Any> require(
            field: String,
            value: T?,
            code: ErrorCode = REQUIRED,
        ): T? = value.also { if (it == null) violations += FieldViolation(field, code) }
    }

    /** Only called once [Checks] found no violations, so every required value is present. */
    private fun <T : Any> T?.checked(): T = checkNotNull(this) { "Value was validated as present" }

    private companion object {
        val REQUIRED = ErrorCode("required")
        val INVALID_JSON = ErrorCode("invalid-json")
        val INVALID_DATE = ErrorCode("invalid-date")
    }
}

/** The part of Shopify's order payload we read. All nullable so missing fields become violations, not exceptions. */
private data class ShopifyOrderJson(
    val id: Long?,
    val name: String?,
    val currency: String?,
    @param:JsonProperty("created_at") val createdAt: String?,
    @param:JsonProperty("line_items") val lineItems: List<ShopifyLineItemJson>?,
)

private data class ShopifyLineItemJson(
    val id: Long?,
    val sku: String?,
    val title: String?,
    val quantity: Int?,
    val price: String?,
)
