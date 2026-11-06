package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class QuantityTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 10_000])
    fun `accepts one or more`(value: Int) {
        assertThat(Quantity.of(value).valueOrNull()?.value).isEqualTo(value)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, Int.MIN_VALUE])
    fun `rejects zero and negative quantities`(value: Int) {
        assertThat(Quantity.of(value))
            .isEqualTo(Result.failure(AppError.Validation(listOf(FieldViolation("quantity", Quantity.INVALID_QUANTITY)))))
    }
}
