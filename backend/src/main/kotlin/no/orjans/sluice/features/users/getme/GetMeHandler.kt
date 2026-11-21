package no.orjans.sluice.features.users.getme

import no.orjans.sluice.shared.CurrentUser
import no.orjans.sluice.shared.Language
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

@Component
class GetMeHandler(
    private val jdbc: JdbcClient,
) {
    fun handle(user: CurrentUser): MeResponse {
        val stored =
            jdbc
                .sql("SELECT language FROM app_users WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", user.issuer)
                .param("subject", user.subject)
                .query { rs, _ -> rs.getString("language") }
                .optional()
                .orElse(null)
        return MeResponse(
            name = user.displayName,
            permissions = user.permissions.sorted(),
            language = Language.ofStoredOrNull(stored)?.tag,
        )
    }
}

data class MeResponse(
    /** For display only: comes from the identity provider on each request and is never stored. */
    val name: String,
    val permissions: List<String>,
    /** The language the user chose, or null if they have not: then the browser language applies, then `en`. */
    val language: String?,
)
