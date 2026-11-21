package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.TestOidcConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.assertj.MockMvcTester

/** The `prod` profile closes what only developers need (PLAN.md, milestone 3). */
@IntegrationTest
@ActiveProfiles(Profiles.PROD)
class ProdProfileIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `the api description and swagger ui are closed`() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.get().uri("/swagger-ui.html")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.NOT_FOUND)
    }

    /** Behind the proxy the app sees http and an internal host; Keycloak only accepts the public https URL. */
    @Test
    fun `login redirects use the public https address the proxy forwards`() {
        val login =
            mvc
                .get()
                .uri("/oauth2/authorization/${TestOidcConfiguration.REGISTRATION_ID}")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "sluice.example.com")
                .exchange()

        assertThat(login.response.getHeader(HttpHeaders.LOCATION))
            .contains("redirect_uri=https://sluice.example.com/login/oauth2/code/keycloak")
        assertThat(login.response.getCookie("XSRF-TOKEN")?.secure).isTrue()
    }

    @Test
    fun `the api still requires a login`() {
        assertThat(mvc.get().uri("/api/app-info")).hasStatus(HttpStatus.UNAUTHORIZED)
    }
}
