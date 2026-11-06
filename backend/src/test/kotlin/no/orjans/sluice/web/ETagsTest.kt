package no.orjans.sluice.web

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponseException

class ETagsTest {
    @Test
    fun `version is sent as a strong etag`() {
        assertThat(ETags.of(3)).isEqualTo("\"3\"")
    }

    @Test
    fun `reads the version back from if-match`() {
        assertThat(ETags.requiredVersion("\"3\"")).isEqualTo(3)
        assertThat(ETags.requiredVersion(" \"42\" ")).isEqualTo(42)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "  "])
    fun `missing if-match is 428 precondition required`(ifMatch: String?) {
        assertThatThrownBy { ETags.requiredVersion(ifMatch) }
            .isInstanceOfSatisfying(ErrorResponseException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["W/\"3\"", "*", "\"3\", \"4\"", "3", "\"abc\"", "\"9999999999999999999\""])
    fun `anything but one strong etag of ours is 412 precondition failed`(ifMatch: String) {
        assertThatThrownBy { ETags.requiredVersion(ifMatch) }
            .isInstanceOfSatisfying(ErrorResponseException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.PRECONDITION_FAILED)
            }
    }
}
