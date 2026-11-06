package no.orjans.sluice.web

import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponseException

/**
 * Optimistic concurrency over HTTP (ADR 0003): the aggregate's `version` is sent as a strong ETag,
 * and a client that changes the resource sends it back in `If-Match`.
 *
 * ```
 * @PostMapping("/jobs/{id}/retry")
 * fun retry(@PathVariable id: String, @RequestHeader(IF_MATCH, required = false) ifMatch: String?) {
 *     val expectedVersion = ETags.requiredVersion(ifMatch)
 *     ...
 * ```
 */
object ETags {
    // Strong ETag with only digits inside the quotes, e.g. "3".
    private val STRONG_VERSION = Regex("^\"(\\d{1,18})\"$")

    fun of(version: Long): String = "\"$version\""

    /**
     * The version the client claims to have.
     * - No `If-Match` → 428 `precondition-required`: a change without a version could overwrite someone else's.
     * - Anything that is not one strong ETag of ours (weak, `*`, a list, garbage) → 412 `precondition-failed`,
     *   because it can never match the current version.
     */
    fun requiredVersion(ifMatch: String?): Long {
        if (ifMatch.isNullOrBlank()) throw ErrorResponseException(HttpStatus.PRECONDITION_REQUIRED)
        val match = STRONG_VERSION.matchEntire(ifMatch.trim()) ?: throw ErrorResponseException(HttpStatus.PRECONDITION_FAILED)
        return match.groupValues[1].toLong()
    }
}
