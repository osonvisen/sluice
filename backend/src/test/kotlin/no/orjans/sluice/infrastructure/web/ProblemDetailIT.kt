package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

@IntegrationTest
class ProblemDetailIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `framework errors are problem details with a code and english text by default`() {
        val result = mvc.get().uri("/api/does-not-exist")

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("not-found")
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Not found")
    }

    @Test
    fun `text follows Accept-Language`() {
        assertThat(mvc.get().uri("/api/does-not-exist").header(HttpHeaders.ACCEPT_LANGUAGE, "nb-NO,nb;q=0.9,en;q=0.5"))
            .bodyJson()
            .extractingPath("$.title")
            .isEqualTo("Ikke funnet")
    }

    @Test
    fun `unsupported language falls back to english`() {
        assertThat(mvc.get().uri("/api/does-not-exist").header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE"))
            .bodyJson()
            .extractingPath("$.title")
            .isEqualTo("Not found")
    }

    @Test
    fun `method not allowed gets its own code`() {
        assertThat(mvc.delete().uri("/api/app-info"))
            .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("method-not-allowed")
    }

    @Test
    fun `a well-formed correlation id is echoed and included in the problem`() {
        val result = mvc.get().uri("/api/does-not-exist").header(CorrelationIdFilter.HEADER, "abc-123")

        assertThat(result).hasHeader(CorrelationIdFilter.HEADER, "abc-123")
        assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo("abc-123")
    }

    @Test
    fun `a malformed correlation id is replaced, so untrusted input never reaches the logs`() {
        val result = mvc.get().uri("/api/app-info").header(CorrelationIdFilter.HEADER, "bad value\nwith newline")

        assertThat(result).headers().hasHeaderSatisfying(CorrelationIdFilter.HEADER) { values ->
            assertThat(values.single()).matches("[0-9a-f-]{36}")
        }
    }
}
