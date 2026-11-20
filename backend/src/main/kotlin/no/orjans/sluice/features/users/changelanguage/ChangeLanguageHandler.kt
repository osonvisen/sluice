package no.orjans.sluice.features.users.changelanguage

import no.orjans.sluice.features.users.UserId
import no.orjans.sluice.shared.CurrentUser
import no.orjans.sluice.shared.Language
import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.atUtc
import no.orjans.sluice.shared.map
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ChangeLanguageHandler(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    /**
     * One upsert: also creates the row if the user has none yet (the `local-noauth` dev user never
     * logs in, and a user from before `app_users` existed may still have a session).
     */
    fun handle(
        user: CurrentUser,
        rawLanguage: String,
    ): Result<Unit> = Language.parse(rawLanguage, "language").map { save(user, it) }

    private fun save(
        user: CurrentUser,
        language: Language,
    ) {
        val now = clock.instant().atUtc()
        jdbc
            .sql(
                """
                INSERT INTO app_users (id, issuer, subject, language, created_at, updated_at)
                VALUES (:id, :issuer, :subject, :language, :now, :now)
                ON CONFLICT (issuer, subject)
                DO UPDATE SET language = EXCLUDED.language, updated_at = EXCLUDED.updated_at, version = app_users.version + 1
                """.trimIndent(),
            ).param("id", UserId.new().value)
            .param("issuer", user.issuer)
            .param("subject", user.subject)
            .param("language", language.tag)
            .param("now", now)
            .update()
    }
}
