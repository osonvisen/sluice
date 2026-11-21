package no.orjans.sluice.infrastructure.security

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

/**
 * Logs each login with the identity provider's subject and the permissions it got: a security event
 * worth keeping (who could do what, when), and the quickest way to see that roles reach the app.
 * The subject is a pseudonymous ID; names and e-mail addresses are never logged.
 */
@Component
class LoginLogger {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onLogin(event: AuthenticationSuccessEvent) {
        val user = event.authentication.principal as? OidcUser ?: return
        log.info("User logged in: subject={} permissions={}", user.subject, event.authentication.authorities.map { it.authority })
    }
}
