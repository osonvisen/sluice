package no.orjans.sluice.support

import no.orjans.sluice.infrastructure.security.RealmRoles
import no.orjans.sluice.infrastructure.security.RealmRolesAuthoritiesMapper
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * Logged-in users for MockMvc, without Keycloak (ADR 0004).
 *
 * Built like a real login: the realm roles are put in the ID token's `realm_access.roles` claim and go
 * through the app's own [RealmRolesAuthoritiesMapper]. A test that uses [operator] therefore also checks
 * which permissions the operator role gives, not a list of authorities copied into the test.
 */
object TestUsers {
    const val ISSUER = "https://idp.test/realms/sluice"

    fun operator(subject: String = "operator-subject"): RequestPostProcessor = withRoles(subject, RealmRoles.OPERATOR)

    fun approver(subject: String = "approver-subject"): RequestPostProcessor = withRoles(subject, RealmRoles.APPROVER)

    /** Logged in, but with no role the app knows: may see nothing (least privilege). */
    fun withoutRoles(subject: String = "no-role-subject"): RequestPostProcessor = withRoles(subject, "offline_access")

    /** A user with exactly these permissions, whatever the roles give: to test one endpoint's check in isolation. */
    fun withPermissions(vararg permissions: String): RequestPostProcessor =
        oidcLogin()
            .clientRegistration(TestOidcConfiguration.KEYCLOAK)
            .idToken { it.issuer(ISSUER).subject("permissions-subject") }
            .authorities(permissions.map(::SimpleGrantedAuthority))

    fun withRoles(
        subject: String,
        vararg roles: String,
    ): RequestPostProcessor {
        val claims =
            mapOf(
                "iss" to ISSUER,
                "sub" to subject,
                "name" to "Test User",
                "preferred_username" to subject,
                RealmRolesAuthoritiesMapper.REALM_ACCESS_CLAIM to mapOf(RealmRolesAuthoritiesMapper.ROLES_KEY to roles.toList()),
            )
        val idToken = OidcIdToken.withTokenValue("test-id-token").claims { it.putAll(claims) }.build()
        val authorities = RealmRolesAuthoritiesMapper().mapAuthorities(listOf(OidcUserAuthority(idToken)))
        return oidcLogin()
            .clientRegistration(TestOidcConfiguration.KEYCLOAK)
            .idToken { it.claims { existing -> existing.putAll(claims) } }
            .authorities(authorities)
    }
}
