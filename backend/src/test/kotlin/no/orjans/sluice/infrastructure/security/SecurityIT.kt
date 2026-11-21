package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.shared.Permissions
import no.orjans.sluice.support.AngularCsrf
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.ScriptedJobHandler
import no.orjans.sluice.support.ShopifyWebhooks
import no.orjans.sluice.support.TestJobs
import no.orjans.sluice.support.TestOidcConfiguration
import no.orjans.sluice.support.TestUsers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * The access rules of ADR 0004, as a specification per role (security standard): what each role can
 * and cannot do, 401 when logged out, 403 without the permission, and CSRF on everything that changes data.
 */
@IntegrationTest
class SecurityIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var jobs: TestJobs

    @Autowired
    lateinit var handler: ScriptedJobHandler

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        handler.reset()
    }

    // --- Logged out ------------------------------------------------------------------------------

    @Test
    fun `logged out, the api answers 401 as a problem and never redirects to the login page`() {
        val result = mvc.get().uri("/api/jobs/${UUID.randomUUID()}")

        assertThat(result)
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
            .doesNotContainHeader(HttpHeaders.LOCATION)
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("unauthorized")
    }

    @Test
    fun `the 401 text follows Accept-Language, although it comes from a security filter`() {
        assertThat(mvc.get().uri("/api/app-info").header(HttpHeaders.ACCEPT_LANGUAGE, "nb"))
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson()
            .extractingPath("$.title")
            .isEqualTo("Ikke autentisert")
    }

    @Test
    fun `logged out, a change with a valid csrf token is still 401`() {
        val id = jobs.dead("logged-out")

        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .with(AngularCsrf.token()),
        ).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the angular app, health and the login entry point are open`() {
        assertThat(mvc.get().uri("/jobs/42")).hasStatusOk()
        assertThat(mvc.get().uri("/health/live")).hasStatusOk()
        assertThat(mvc.get().uri("/oauth2/authorization/${TestOidcConfiguration.REGISTRATION_ID}"))
            .hasStatus(HttpStatus.FOUND)
            .headers()
            .hasHeaderSatisfying(HttpHeaders.LOCATION) {
                assertThat(it.single())
                    .startsWith("${TestUsers.ISSUER}/protocol/openid-connect/auth?")
                    .describedAs("PKCE, also for the confidential client")
                    .contains("code_challenge=", "code_challenge_method=S256")
            }
    }

    // --- Per role ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["operator", "approver"])
    fun `operator and approver can read a job and run it again`(role: String) {
        val user = userWith(role)
        val id = jobs.dead("can-$role")

        val read =
            mvc
                .get()
                .uri("/api/jobs/$id")
                .with(user)
                .exchange()
        assertThat(read).hasStatusOk()

        val etag = etagOf(read)
        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, etag)
                .with(user)
                .with(AngularCsrf.token()),
        ).hasStatusOk()
    }

    @Test
    fun `a user without a known role can neither read nor retry a job`() {
        val id = jobs.dead("no-role")

        assertThat(mvc.get().uri("/api/jobs/$id").with(TestUsers.withoutRoles()))
            .hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("forbidden")
        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .with(TestUsers.withoutRoles())
                .with(AngularCsrf.token()),
        ).hasStatus(HttpStatus.FORBIDDEN)
    }

    /** No role is read-only today, but the endpoints must check the specific permission, not "any permission". */
    @Test
    fun `reading does not grant retrying, each endpoint checks its own permission`() {
        val readOnly = TestUsers.withPermissions(Permissions.JOBS_READ)
        val id = jobs.dead("read-only")
        val read =
            mvc
                .get()
                .uri("/api/jobs/$id")
                .with(readOnly)
                .exchange()
        assertThat(read).hasStatusOk()

        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, etagOf(read))
                .with(readOnly)
                .with(AngularCsrf.token()),
        ).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mvc.get().uri("/api/jobs/$id").with(readOnly)).bodyJson().extractingPath("$.status").isEqualTo("DEAD")
    }

    // --- CSRF --------------------------------------------------------------------------------------

    @Test
    fun `retry without a csrf token is 403 csrf-token-invalid, also for a user who may retry`() {
        val id = jobs.dead("no-csrf")

        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .with(TestUsers.operator()),
        ).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("csrf-token-invalid")
    }

    /**
     * What Angular does: read the `XSRF-TOKEN` cookie from an earlier response and send it back in the
     * `X-XSRF-TOKEN` header. No `csrf()` helper here, so the real cookie and header names are tested.
     */
    @Test
    fun `retry works with the XSRF-TOKEN cookie sent back as the X-XSRF-TOKEN header`() {
        val operator = TestUsers.operator()
        val id = jobs.dead("csrf-pair")
        val read =
            mvc
                .get()
                .uri("/api/jobs/$id")
                .with(operator)
                .exchange()
        val cookie = checkNotNull(read.response.getCookie(CSRF_COOKIE)) { "No $CSRF_COOKIE cookie on a GET" }
        assertThat(cookie.isHttpOnly).describedAs("Angular must be able to read the cookie").isFalse()

        val retry =
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, etagOf(read))
                .cookie(cookie)
                .header(CSRF_HEADER, cookie.value)
                .with(operator)

        assertThat(retry).hasStatusOk()
    }

    @Test
    fun `a csrf header that does not match the cookie is rejected`() {
        val operator = TestUsers.operator()
        val id = jobs.dead("csrf-mismatch")
        val cookie =
            checkNotNull(
                mvc
                    .get()
                    .uri("/api/jobs/$id")
                    .with(operator)
                    .exchange()
                    .response
                    .getCookie(CSRF_COOKIE),
            )

        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, "\"1\"")
                .cookie(cookie)
                .header(CSRF_HEADER, "not-the-token")
                .with(operator),
        ).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("csrf-token-invalid")
    }

    @Test
    fun `webhooks need neither a login nor a csrf token, the hmac protects them`() {
        assertThat(ShopifyWebhooks.send(mvc)).hasStatusOk()
        assertThat(ShopifyWebhooks.send(mvc, signature = "bad")).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    // --- Logout ------------------------------------------------------------------------------------

    @Test
    fun `logout ends the session and hands the identity provider's logout url to the client`() {
        val result =
            mvc
                .post()
                .uri("/logout")
                .with(TestUsers.operator())
                .with(AngularCsrf.token())
                .exchange()

        assertThat(result).hasStatus(HttpStatus.ACCEPTED)
        assertThat(result.response.getHeader(HttpHeaders.LOCATION))
            .startsWith("${TestOidcConfiguration.END_SESSION_URI}?")
            .contains("id_token_hint=")
            .contains("post_logout_redirect_uri=")
    }

    private fun etagOf(read: MvcTestResult): String = checkNotNull(read.response.getHeader(HttpHeaders.ETAG))

    private fun userWith(role: String): RequestPostProcessor =
        when (role) {
            "operator" -> TestUsers.operator()
            "approver" -> TestUsers.approver()
            else -> error("Unknown role $role")
        }

    private companion object {
        const val CSRF_COOKIE = "XSRF-TOKEN"
        const val CSRF_HEADER = "X-XSRF-TOKEN"
    }
}
