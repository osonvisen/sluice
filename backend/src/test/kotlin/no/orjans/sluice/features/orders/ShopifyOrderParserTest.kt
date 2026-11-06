package no.orjans.sluice.features.orders

import no.orjans.sluice.features.orders.receiveshopifyorder.ShopifyOrderParser
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.valueOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.math.BigDecimal
import java.time.Instant

class ShopifyOrderParserTest {
    private val parser = ShopifyOrderParser(jacksonMapperBuilder().build())
    private val sample = checkNotNull(javaClass.getResource("/shopify/orders-create.json")).readBytes()

    @Test
    fun `reads the fields the erp needs from a real shopify payload`() {
        val order = parser.parse(sample).valueOrNull()

        assertThat(order).isNotNull
        assertThat(order?.externalOrderId).isEqualTo(5847329874321)
        assertThat(order?.orderNumber).isEqualTo("#1042")
        assertThat(order?.currency?.currencyCode).isEqualTo("NOK")
        assertThat(order?.orderedAt).isEqualTo(Instant.parse("2026-11-05T13:32:11Z"))
        assertThat(order?.lines?.map { it.sku }).containsExactly("SOCK-WOOL-GREY-3942", "HAT-KNIT-NAVY")
        assertThat(order?.lines?.map { it.quantity.value }).containsExactly(2, 1)
        assertThat(order?.lines?.map { it.unitPrice.amount }).containsExactly(BigDecimal("149.00"), BigDecimal("499.00"))
    }

    @Test
    fun `keeps no customer data`() {
        val order = parser.parse(sample).valueOrNull()

        assertThat(order.toString()).doesNotContain("Nordmann", "example.com", "Eksempelveien", "+4799999999")
    }

    @Test
    fun `a missing sku is kept, so the import job can reject the order visibly`() {
        val body = """{"id":1,"name":"#1","currency":"NOK","created_at":"2026-11-05T14:32:11+01:00",
            "line_items":[{"id":10,"title":"Sokker","quantity":1,"price":"10.00"}]}"""

        assertThat(
            parser
                .parse(body.toByteArray())
                .valueOrNull()
                ?.lines
                ?.single()
                ?.sku,
        ).isNull()
    }

    @Test
    fun `lists every problem at once`() {
        val body = """{"name":"","currency":"XYZ","created_at":"yesterday",
            "line_items":[{"id":10,"title":"Sokker","quantity":0,"price":"abc"}]}"""

        val result = parser.parse(body.toByteArray())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val violations = ((result as Result.Failure).error as AppError.Validation).violations.map { it.field }
        assertThat(violations).containsExactlyInAnyOrder("id", "name", "currency", "created_at", "line_items[0].quantity")
    }

    @Test
    fun `a price with more decimals than the currency is rejected, not rounded`() {
        val body = """{"id":1,"name":"#1","currency":"NOK","created_at":"2026-11-05T14:32:11+01:00",
            "line_items":[{"id":10,"sku":"A","title":"Sokker","quantity":1,"price":"10.005"}]}"""

        val result = parser.parse(body.toByteArray())

        val violations = ((result as Result.Failure).error as AppError.Validation).violations.map { it.field }
        assertThat(violations).containsExactly("line_items[0].price")
    }

    @Test
    fun `invalid json is a validation error that does not echo the body`() {
        val result = parser.parse("""{"id": "Kari Nordmann""".toByteArray())

        assertThat(result.toString()).contains("invalid-json").doesNotContain("Kari")
    }
}
