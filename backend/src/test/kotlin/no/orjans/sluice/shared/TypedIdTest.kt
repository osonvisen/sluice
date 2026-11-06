package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class TypedIdTest {
    @JvmInline
    value class SampleId private constructor(
        override val value: UUID,
    ) : TypedId {
        companion object {
            fun parse(raw: String): Result<SampleId> = TypedId.parse(raw, "sampleId", ::SampleId)
        }
    }

    @Test
    fun `parses a canonical uuid`() {
        val raw = "0b8f3c1e-6c1a-4b7e-9d65-1f2a3b4c5d6e"

        val result = SampleId.parse(raw)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value.value).isEqualTo(UUID.fromString(raw))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not-a-uuid", "1-1-1-1-1", "0b8f3c1e6c1a4b7e9d651f2a3b4c5d6e", " 0b8f3c1e-6c1a-4b7e-9d65-1f2a3b4c5d6e"])
    fun `rejects anything else as a validation error on the field`(raw: String) {
        val expected = AppError.Validation(listOf(FieldViolation("sampleId", TypedId.INVALID_ID)))

        assertThat(SampleId.parse(raw)).isEqualTo(Result.failure(expected))
    }
}
