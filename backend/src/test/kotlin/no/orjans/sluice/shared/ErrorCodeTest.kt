package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ErrorCodeTest {
    @ParameterizedTest
    @ValueSource(strings = ["not-found", "job-not-retryable", "http-418", "conflict"])
    fun `accepts lower-case kebab-case`(code: String) {
        assertThatCode { ErrorCode(code) }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "NotFound", "not_found", "-leading", "trailing-", "double--dash", "1-starts-with-digit", "has space"])
    fun `rejects anything else`(code: String) {
        assertThatThrownBy { ErrorCode(code) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
