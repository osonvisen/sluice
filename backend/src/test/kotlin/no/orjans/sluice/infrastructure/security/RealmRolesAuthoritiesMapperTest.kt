package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.shared.Permissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority

class RealmRolesAuthoritiesMapperTest {
    private val mapper = RealmRolesAuthoritiesMapper()

    @Test
    fun `operator may read and retry jobs`() {
        assertThat(permissions(idToken(RealmRoles.OPERATOR))).containsExactlyInAnyOrder(Permissions.JOBS_READ, Permissions.JOBS_RETRY)
    }

    @Test
    fun `approver may do what an operator may, and decide approvals`() {
        assertThat(permissions(idToken(RealmRoles.APPROVER)))
            .containsExactlyInAnyOrder(Permissions.JOBS_READ, Permissions.JOBS_RETRY, Permissions.APPROVALS_DECIDE)
    }

    @Test
    fun `several roles give the union of their permissions`() {
        assertThat(permissions(idToken(RealmRoles.OPERATOR, RealmRoles.APPROVER)))
            .containsExactlyInAnyOrder(Permissions.JOBS_READ, Permissions.JOBS_RETRY, Permissions.APPROVALS_DECIDE)
    }

    @Test
    fun `unknown roles and a missing claim give no permissions`() {
        assertThat(permissions(idToken("offline_access", "uma_authorization"))).isEmpty()
        assertThat(permissions(OidcUserAuthority(token(emptyMap())))).isEmpty()
    }

    @Test
    fun `roles are read from userinfo when the id token lacks them`() {
        val userInfo = OidcUserInfo(mapOf("sub" to "s", "realm_access" to mapOf("roles" to listOf(RealmRoles.OPERATOR))))

        assertThat(permissions(OidcUserAuthority(token(emptyMap()), userInfo))).contains(Permissions.JOBS_RETRY)
    }

    @Test
    fun `authorities other than the oidc user are dropped, so only permissions remain`() {
        val mapped = mapper.mapAuthorities(listOf(SimpleGrantedAuthority("SCOPE_openid"), idToken(RealmRoles.OPERATOR)))

        assertThat(mapped.map { it.authority }).containsExactlyInAnyOrder(Permissions.JOBS_READ, Permissions.JOBS_RETRY)
    }

    private fun permissions(vararg authorities: GrantedAuthority) = mapper.mapAuthorities(authorities.toList()).map { it.authority }

    private fun idToken(vararg roles: String) = OidcUserAuthority(token(mapOf("realm_access" to mapOf("roles" to roles.toList()))))

    private fun token(claims: Map<String, Any>) =
        OidcIdToken
            .withTokenValue("t")
            .subject("s")
            .claims { it.putAll(claims) }
            .build()
}
