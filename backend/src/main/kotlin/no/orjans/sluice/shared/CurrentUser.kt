package no.orjans.sluice.shared

/**
 * The logged-in person making the request, as the identity provider describes them.
 *
 * [issuer] and [subject] together identify the person (OIDC); never the name or e-mail. [displayName]
 * is for showing only and is never stored. [permissions] are what the user's roles grant (see [Permissions]).
 */
data class CurrentUser(
    val issuer: String,
    val subject: String,
    val displayName: String,
    val permissions: Set<String>,
) {
    // The name is personal data: keep it out of logs, even when an object is logged by accident.
    override fun toString(): String = "CurrentUser(issuer=$issuer, subject=$subject, permissions=$permissions)"
}
