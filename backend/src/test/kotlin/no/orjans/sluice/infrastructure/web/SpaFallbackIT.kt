package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester

/** Uses the test `static/index.html` in place of the real Angular build. */
@IntegrationTest
class SpaFallbackIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `deep link to a client route serves index html`() {
        assertThat(mvc.get().uri("/jobs/42"))
            .hasStatusOk()
            .bodyText()
            .contains("sluice-test-index")
    }

    @Test
    fun `unknown api path is a 404 problem, not the app shell`() {
        assertThat(mvc.get().uri("/api/does-not-exist"))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("not-found")
    }

    @Test
    fun `missing static file is a 404`() {
        assertThat(mvc.get().uri("/missing-chunk.js")).hasStatus(HttpStatus.NOT_FOUND)
    }
}
