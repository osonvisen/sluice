package no.orjans.sluice.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.web.filter.OncePerRequestFilter

object Profiles {
    const val PROD = "prod"

    /** Development without Keycloak: every request is [LocalNoAuthFilter]'s dev user. Never together with [PROD]. */
    const val LOCAL_NOAUTH = "local-noauth"
}

/**
 * Logs every request in as a fixed dev user with **both** roles (`local-noauth` only, ADR 0004).
 *
 * The user is built the same way a Keycloak login is: an OIDC user whose `realm_access.roles` claim goes
 * through [RealmRolesAuthoritiesMapper]. Everything after login (permissions, `/api/me`) therefore runs
 * the same code with or without Keycloak.
 */
class LocalNoAuthFilter : OncePerRequestFilter() {
    private val devUser: Authentication = devUserAuthentication()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val holder = SecurityContextHolder.getContextHolderStrategy()
        if (holder.context.authentication == null) {
            holder.context = holder.createEmptyContext().apply { authentication = devUser }
        }
        filterChain.doFilter(request, response)
    }

    private fun devUserAuthentication(): Authentication {
        val idToken =
            OidcIdToken
                .withTokenValue(REGISTRATION_ID)
                .issuer(ISSUER)
                .subject(SUBJECT)
                .claim(NAME_CLAIM, DISPLAY_NAME)
                .claim(
                    RealmRolesAuthoritiesMapper.REALM_ACCESS_CLAIM,
                    mapOf(RealmRolesAuthoritiesMapper.ROLES_KEY to listOf(RealmRoles.OPERATOR, RealmRoles.APPROVER)),
                ).build()
        val authorities = RealmRolesAuthoritiesMapper().mapAuthorities(listOf(OidcUserAuthority(idToken)))
        return OAuth2AuthenticationToken(DefaultOidcUser(authorities, idToken, NAME_CLAIM), authorities, REGISTRATION_ID)
    }

    companion object {
        /** Not a URL on purpose: it can never be mistaken for a real identity provider's issuer. */
        const val ISSUER = "local-noauth"
        const val SUBJECT = "local-dev-user"
        private const val REGISTRATION_ID = "local-noauth"
        private const val NAME_CLAIM = "name"
        private const val DISPLAY_NAME = "Local developer"
    }
}

/**
 * Refuses to start when `local-noauth` is combined with `prod`: the profile switches login off, so it
 * must never reach a real environment, whatever the configuration says (ADR 0004).
 *
 * An environment post-processor runs before any bean is created, so the app stops before it opens a
 * port or a database connection. It runs after the config files are read, so profiles activated
 * there are seen too.
 */
class LocalNoAuthGuard :
    EnvironmentPostProcessor,
    Ordered {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        check(!(environment.matchesProfiles(Profiles.LOCAL_NOAUTH) && environment.matchesProfiles(Profiles.PROD))) {
            "The '${Profiles.LOCAL_NOAUTH}' profile turns login off and must never run with '${Profiles.PROD}'."
        }
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}
