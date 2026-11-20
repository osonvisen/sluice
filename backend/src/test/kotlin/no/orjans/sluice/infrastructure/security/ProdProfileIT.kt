package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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

    @Test
    fun `the api still requires a login`() {
        assertThat(mvc.get().uri("/api/app-info")).hasStatus(HttpStatus.UNAUTHORIZED)
    }
}
