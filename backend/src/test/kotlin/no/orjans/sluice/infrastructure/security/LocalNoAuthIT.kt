package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.UUID

@IntegrationTest
@ActiveProfiles(Profiles.LOCAL_NOAUTH)
class LocalNoAuthIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `every request is the dev user, who may read jobs without logging in`() {
        // 404, not 401 or 403: the request got past the login and the permission check.
        assertThat(mvc.get().uri("/api/jobs/${UUID.randomUUID()}")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `csrf protection still applies, so the frontend behaves as with a real login`() {
        assertThat(mvc.post().uri("/api/jobs/${UUID.randomUUID()}/retry").header(HttpHeaders.IF_MATCH, "\"0\""))
            .hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("csrf-token-invalid")
    }
}
