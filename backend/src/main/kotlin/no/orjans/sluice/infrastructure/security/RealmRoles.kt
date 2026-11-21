package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.shared.Permissions
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority

/** The realm roles from Keycloak (`keycloak/realm-sluice.json`) and the permissions each one grants (ADR 0004). */
object RealmRoles {
    const val OPERATOR = "operator"
    const val APPROVER = "approver"

    private val PERMISSIONS_BY_ROLE: Map<String, Set<String>> =
        mapOf(
            OPERATOR to setOf(Permissions.JOBS_READ, Permissions.JOBS_RETRY),
            APPROVER to setOf(Permissions.JOBS_READ, Permissions.JOBS_RETRY, Permissions.APPROVALS_DECIDE),
        )

    /** Unknown roles grant nothing (least privilege), so a role added in Keycloak never opens anything by itself. */
    fun permissionsFor(roles: Collection<String>): Set<String> = roles.flatMapTo(sortedSetOf()) { PERMISSIONS_BY_ROLE[it].orEmpty() }
}

/**
 * Replaces the authorities Spring Security gives an OIDC login with our permissions.
 *
 * The roles are read from the `realm_access.roles` claim. `oauth2Login` sees the **ID token** and the
 * userinfo response, not the access token, so the realm puts the claim in both (protocol mapper on the
 * `sluice-web` client). Both sources are read, so either one is enough.
 */
class RealmRolesAuthoritiesMapper : GrantedAuthoritiesMapper {
    override fun mapAuthorities(authorities: Collection<GrantedAuthority>): Collection<GrantedAuthority> {
        val roles =
            authorities
                .filterIsInstance<OidcUserAuthority>()
                .flatMap { realmRoles(it.idToken.claims) + realmRoles(it.userInfo?.claims.orEmpty()) }
        return RealmRoles.permissionsFor(roles).map(::SimpleGrantedAuthority)
    }

    // Claims are untyped JSON: anything that is not a map with a list of strings counts as no roles.
    private fun realmRoles(claims: Map<String, Any?>): List<String> {
        val roles = (claims[REALM_ACCESS_CLAIM] as? Map<*, *>)?.get(ROLES_KEY) as? Collection<*>
        return roles.orEmpty().filterIsInstance<String>()
    }

    companion object {
        const val REALM_ACCESS_CLAIM = "realm_access"
        const val ROLES_KEY = "roles"
    }
}
