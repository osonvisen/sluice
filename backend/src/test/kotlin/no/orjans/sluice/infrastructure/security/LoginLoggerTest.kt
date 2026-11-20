package no.orjans.sluice.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser

@ExtendWith(OutputCaptureExtension::class)
class LoginLoggerTest {
    @Test
    fun `a login is logged with subject and permissions, never name or e-mail`(output: CapturedOutput) {
        val idToken =
            OidcIdToken
                .withTokenValue("t")
                .subject("4f1c-subject")
                .claim("name", "Ola Nordmann")
                .claim("email", "ola@example.com")
                .build()
        val authorities = listOf(SimpleGrantedAuthority("jobs.read"))
        val login = OAuth2AuthenticationToken(DefaultOidcUser(authorities, idToken), authorities, "keycloak")

        LoginLogger().onLogin(AuthenticationSuccessEvent(login))

        assertThat(output).contains("User logged in: subject=4f1c-subject permissions=[jobs.read]")
        assertThat(output).doesNotContain("Ola Nordmann").doesNotContain("ola@example.com")
    }
}
