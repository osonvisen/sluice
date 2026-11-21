package no.orjans.sluice.features.users.registeruser

import no.orjans.sluice.features.users.UserId
import no.orjans.sluice.shared.CurrentUser
import no.orjans.sluice.shared.atUtc
import no.orjans.sluice.web.CurrentUsers
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Creates the user's row in `app_users` on their first login (ADR 0011). Later logins change nothing.
 *
 * With `local-noauth` there is no login event; the dev user's row is created when it first saves a setting.
 */
@Component
class RegisterUserOnLogin(
    private val jdbc: JdbcClient,
    private val currentUsers: CurrentUsers,
    private val clock: Clock,
) {
    @EventListener
    fun onLogin(event: AuthenticationSuccessEvent) {
        if (event.authentication.principal !is OidcUser) return
        register(currentUsers.from(event.authentication))
    }

    /** Idempotent: a second login, or two at the same time, hit the unique constraint and insert nothing. */
    fun register(user: CurrentUser) {
        val now = clock.instant().atUtc()
        jdbc
            .sql(
                """
                INSERT INTO app_users (id, issuer, subject, created_at, updated_at)
                VALUES (:id, :issuer, :subject, :now, :now)
                ON CONFLICT (issuer, subject) DO NOTHING
                """.trimIndent(),
            ).param("id", UserId.new().value)
            .param("issuer", user.issuer)
            .param("subject", user.subject)
            .param("now", now)
            .update()
    }
}
