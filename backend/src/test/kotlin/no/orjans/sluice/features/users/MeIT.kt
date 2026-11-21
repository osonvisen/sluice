package no.orjans.sluice.features.users

import no.orjans.sluice.support.AngularCsrf
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.TestUsers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.request.RequestPostProcessor

/** `/api/me` and the language stored per user (ADR 0011). */
@IntegrationTest
class MeIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() = cleaner.clean()

    @Test
    fun `me has the name, the role's permissions and no language before one is chosen`() {
        val body = assertThat(me(TestUsers.approver())).hasStatusOk().bodyJson()

        body.extractingPath("$.name").isEqualTo("Test User")
        body.extractingPath("$.permissions").asArray().containsExactly("approvals.decide", "jobs.read", "jobs.retry")
        body.extractingPath("$.language").isNull()
    }

    @Test
    fun `a user without permissions may still ask who they are`() {
        assertThat(me(TestUsers.withoutRoles()))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.permissions")
            .asArray()
            .isEmpty()
    }

    @Test
    fun `logged out, me is a 401 so the app knows to log in`() {
        assertThat(mvc.get().uri("/api/me")).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the chosen language is stored and returned, and can be changed again`() {
        val user = TestUsers.operator("language-subject")

        assertThat(changeLanguage(user, "nb")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(me(user)).bodyJson().extractingPath("$.language").isEqualTo("nb")

        assertThat(changeLanguage(user, "en")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(me(user)).bodyJson().extractingPath("$.language").isEqualTo("en")
        assertThat(rows("language-subject")).isEqualTo(1)
    }

    @Test
    fun `the language is per person, not shared`() {
        changeLanguage(TestUsers.operator("person-a"), "nb")

        assertThat(me(TestUsers.operator("person-b"))).bodyJson().extractingPath("$.language").isNull()
    }

    @Test
    fun `an unsupported or regional language tag is rejected`() {
        listOf("de", "nb-NO", "NB", "").forEach { tag ->
            assertThat(changeLanguage(TestUsers.operator(), tag))
                .describedAs("language '%s'", tag)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.violations[0].field")
                .isEqualTo("language")
        }
    }

    @Test
    fun `changing the language needs a csrf token`() {
        assertThat(
            mvc
                .put()
                .uri("/api/me/language")
                .with(TestUsers.operator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"language":"nb"}"""),
        ).hasStatus(HttpStatus.FORBIDDEN)
    }

    /** RegisterUserOnLogin listens for Spring Security's login event; this publishes it as a real login would. */
    @Test
    fun `the first login creates the user's row, later logins do not add one`() {
        repeat(2) { events.publishEvent(AuthenticationSuccessEvent(login("first-login-subject"))) }

        assertThat(rows("first-login-subject")).isEqualTo(1)
    }

    /** Data minimisation (ADR 0011): the table can hold no name or e-mail, whatever the login carried. */
    @Test
    fun `app_users stores only issuer, subject, language and timestamps`() {
        val columns =
            jdbc
                .sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'app_users'")
                .query(String::class.java)
                .list()

        assertThat(columns).containsExactlyInAnyOrder("id", "issuer", "subject", "language", "created_at", "updated_at", "version")
    }

    private fun me(user: RequestPostProcessor) = mvc.get().uri("/api/me").with(user)

    private fun changeLanguage(
        user: RequestPostProcessor,
        language: String,
    ) = mvc
        .put()
        .uri("/api/me/language")
        .with(user)
        .with(AngularCsrf.token())
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"language":"$language"}""")

    private fun rows(subject: String): Int =
        jdbc
            .sql("SELECT count(*) FROM app_users WHERE subject = :subject")
            .param("subject", subject)
            .query(Int::class.java)
            .single()

    private fun login(subject: String): OAuth2AuthenticationToken {
        val idToken =
            OidcIdToken
                .withTokenValue("t")
                .issuer(TestUsers.ISSUER)
                .subject(subject)
                .claim("name", "Ola Nordmann")
                .claim("email", "ola@example.com")
                .build()
        return OAuth2AuthenticationToken(DefaultOidcUser(emptyList(), idToken), emptyList(), "keycloak")
    }
}
