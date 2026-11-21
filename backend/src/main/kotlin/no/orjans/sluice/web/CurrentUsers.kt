package no.orjans.sluice.web

import no.orjans.sluice.shared.CurrentUser
import no.orjans.sluice.shared.Permissions
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.StandardClaimNames
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

/**
 * Turns Spring Security's login into a [CurrentUser], so endpoints and handlers never touch the security API.
 *
 * Every login is an OIDC user: a Keycloak login, a `spring-security-test` `oidcLogin()`, or the
 * `local-noauth` dev user, which is built the same way.
 */
@Component
class CurrentUsers {
    /** The user of the current request. Only call it behind `@PreAuthorize`, where a login is guaranteed. */
    fun current(): CurrentUser = from(checkNotNull(SecurityContextHolder.getContext().authentication) { "No login on this request" })

    fun from(authentication: Authentication): CurrentUser {
        val user = authentication.principal as? OidcUser ?: error("Expected an OIDC login, got ${authentication.javaClass.simpleName}")
        // Claims as strings: the issuer of the local-noauth user is not a URL, and getIssuer() would require one.
        val subject = checkNotNull(user.getClaimAsString(IdTokenClaimNames.SUB)) { "ID token without subject" }
        return CurrentUser(
            issuer = checkNotNull(user.getClaimAsString(IdTokenClaimNames.ISS)) { "ID token without issuer" },
            subject = subject,
            displayName =
                user.getClaimAsString(StandardClaimNames.NAME) ?: user.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME) ?: subject,
            // Only our permissions: Spring Security may add authorities of its own (e.g. how the user authenticated).
            permissions = authentication.authorities.mapNotNull { it.authority }.filterTo(sortedSetOf()) { it in Permissions.ALL },
        )
    }
}
