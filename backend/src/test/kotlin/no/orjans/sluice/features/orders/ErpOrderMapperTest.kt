package no.orjans.sluice.features.orders

import no.orjans.sluice.features.orders.importorder.ErpMapping
import no.orjans.sluice.features.orders.importorder.ErpOrderMapper
import no.orjans.sluice.features.orders.importorder.OrderForImport
import no.orjans.sluice.features.orders.importorder.StoredLine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ErpOrderMapperTest {
    private val mapper = ErpOrderMapper()
    private val orderId = OrderId.of(UUID.fromString("0b8f3c1e-6c1a-4b7e-9d65-1f2a3b4c5d6e"))

    private fun order(vararg lines: StoredLine) =
        OrderForImport(orderId, "#1042", "NOK", Instant.parse("2026-11-05T13:32:11Z"), OrderStatus.RECEIVED, lines.toList())

    private fun line(
        number: Int,
        sku: String? = "SKU-$number",
        quantity: Int = 1,
        price: String = "10.00",
    ) = StoredLine(number, sku, quantity, BigDecimal(price))

    @Test
    fun `maps every line with sku, quantity and price in the order's currency`() {
        val mapping = mapper.map(order(line(1, "SOCK", 2, "149"), line(2, "HAT", 1, "499.00")))

        assertThat(mapping).isInstanceOf(ErpMapping.Ready::class.java)
        val erp = (mapping as ErpMapping.Ready).order
        assertThat(erp.orderReference).isEqualTo(orderId)
        assertThat(erp.currency.currencyCode).isEqualTo("NOK")
        assertThat(erp.lines.map { "${it.lineNumber} ${it.sku} ${it.quantity} ${it.unitPrice}" })
            .containsExactly("1 SOCK 2 149.00 NOK", "2 HAT 1 499.00 NOK")
    }

    @Test
    fun `reports every invalid line, not just the first`() {
        val mapping = mapper.map(order(line(1, sku = null), line(2), line(3, sku = "has space"), line(4, price = "1.005")))

        assertThat(mapping).isInstanceOf(ErpMapping.Invalid::class.java)
        assertThat((mapping as ErpMapping.Invalid).problems.map { "${it.lineNumber} ${it.code}" })
            .containsExactly("1 MISSING_SKU", "3 INVALID_SKU", "4 INVALID_PRICE")
    }

    @Test
    fun `an order without lines is invalid`() {
        assertThat(mapper.map(order())).isInstanceOf(ErpMapping.Invalid::class.java)
    }
}
