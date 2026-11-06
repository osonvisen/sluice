package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `keeps the currency's number of decimals, so equal amounts are equal`() {
        val a = Money.of(BigDecimal("199"), "NOK").valueOrNull()
        val b = Money.of(BigDecimal("199.00"), "NOK").valueOrNull()

        assertThat(a).isNotNull.isEqualTo(b)
        assertThat(a?.amount).isEqualTo(BigDecimal("199.00"))
    }

    @Test
    fun `accepts zero, for example a free gift`() {
        assertThat(Money.of(BigDecimal.ZERO, "NOK").valueOrNull()?.amount).isEqualByComparingTo("0")
    }

    @Test
    fun `rejects negative amounts`() {
        assertThat(Money.of(BigDecimal("-1.00"), "NOK"))
            .isEqualTo(Result.failure(AppError.Validation(listOf(FieldViolation("amount", Money.INVALID_AMOUNT)))))
    }

    @Test
    fun `rejects more decimals than the currency has instead of rounding`() {
        assertThat(Money.of(BigDecimal("199.005"), "NOK").valueOrNull()).isNull()
        assertThat(Money.of(BigDecimal("100.5"), "JPY").valueOrNull()).isNull()
    }

    @Test
    fun `rejects unknown currencies`() {
        assertThat(Money.of(BigDecimal.ONE, "XYZ"))
            .isEqualTo(Result.failure(AppError.Validation(listOf(FieldViolation("currency", Money.INVALID_CURRENCY)))))
    }
}
