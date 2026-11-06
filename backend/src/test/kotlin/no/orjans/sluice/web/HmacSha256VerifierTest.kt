package no.orjans.sluice.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class HmacSha256VerifierTest {
    private val verifier = HmacSha256Verifier()
    private val body = """{"id":1}""".toByteArray()

    // Computed independently: printf '%s' '{"id":1}' | openssl dgst -sha256 -hmac 'shpss_test_secret' -binary | base64
    private val signature = "K2XDCjoIIQnsCAW9wJNjHt4VwaiFCMoHAkXVA+JPSq0="

    @Test
    fun `accepts the signature of the exact body`() {
        assertThat(verifier.isValid(body, signature, SECRET)).isTrue()
    }

    @Test
    fun `rejects a body changed by one byte`() {
        assertThat(verifier.isValid("""{"id":2}""".toByteArray(), signature, SECRET)).isFalse()
    }

    @Test
    fun `rejects a signature made with another secret`() {
        assertThat(verifier.isValid(body, signature, "another-secret")).isFalse()
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "not base64 !!", "AAAA"])
    fun `rejects missing, malformed or wrong-length signatures`(value: String?) {
        assertThat(verifier.isValid(body, value, SECRET)).isFalse()
    }

    private companion object {
        const val SECRET = "shpss_test_secret"
    }
}
