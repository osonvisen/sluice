package no.orjans.sluice.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType

/**
 * The Keycloak client registration with fixed endpoints. With an `issuer-uri`, Spring Boot would fetch
 * the provider's discovery document at startup, and tests never start Keycloak (ADR 0004).
 * Boot backs off its own registration repository when this bean exists.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestOidcConfiguration {
    @Bean
    fun clientRegistrationRepository() = InMemoryClientRegistrationRepository(KEYCLOAK)

    companion object {
        const val REGISTRATION_ID = "keycloak"
        const val END_SESSION_URI = "${TestUsers.ISSUER}/protocol/openid-connect/logout"

        /** Also used by [TestUsers], so a test login looks like it came through this registration. */
        val KEYCLOAK: ClientRegistration =
            ClientRegistration
                .withRegistrationId(REGISTRATION_ID)
                .clientId("sluice-web")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .issuerUri(TestUsers.ISSUER)
                .authorizationUri("${TestUsers.ISSUER}/protocol/openid-connect/auth")
                .tokenUri("${TestUsers.ISSUER}/protocol/openid-connect/token")
                .jwkSetUri("${TestUsers.ISSUER}/protocol/openid-connect/certs")
                .userInfoUri("${TestUsers.ISSUER}/protocol/openid-connect/userinfo")
                .userNameAttributeName("preferred_username")
                // Read by RP-initiated logout, as it would be from Keycloak's discovery document.
                .providerConfigurationMetadata(mapOf("end_session_endpoint" to END_SESSION_URI))
                .build()
    }
}
