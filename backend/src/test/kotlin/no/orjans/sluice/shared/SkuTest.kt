package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class SkuTest {
    @ParameterizedTest
    @ValueSource(strings = ["TSHIRT-RED-M", "1001", "abc.def/2", "A_1"])
    fun `accepts common sku formats`(raw: String) {
        assertThat(Sku.parse(raw).valueOrNull()?.value).isEqualTo(raw)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertThat(Sku.parse("  TSHIRT-RED-M ").valueOrNull()?.value).isEqualTo("TSHIRT-RED-M")
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "   ", "has space", "-starts-with-dash", "ÆØÅ"])
    fun `rejects missing or malformed skus as a validation error`(raw: String?) {
        assertThat(Sku.parse(raw, "lineItems[0].sku"))
            .isEqualTo(Result.failure(AppError.Validation(listOf(FieldViolation("lineItems[0].sku", Sku.INVALID_SKU)))))
    }

    @Test
    fun `rejects skus longer than the maximum`() {
        assertThat(Sku.parse("A".repeat(Sku.MAX_LENGTH)).valueOrNull()).isNotNull()
        assertThat(Sku.parse("A".repeat(Sku.MAX_LENGTH + 1)).valueOrNull()).isNull()
    }
}
