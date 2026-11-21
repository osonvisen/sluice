package no.orjans.sluice.shared

/**
 * What a user may do. Code checks permissions, never roles: a role is a named bundle of permissions,
 * mapped in one place when the user logs in (ADR 0004).
 *
 * Constants, so they can be used in annotations:
 * `@PreAuthorize("hasAuthority('${Permissions.JOBS_READ}')")`.
 */
object Permissions {
    const val JOBS_READ = "jobs.read"
    const val JOBS_RETRY = "jobs.retry"
    const val APPROVALS_DECIDE = "approvals.decide"

    val ALL: Set<String> = setOf(JOBS_READ, JOBS_RETRY, APPROVALS_DECIDE)
}
