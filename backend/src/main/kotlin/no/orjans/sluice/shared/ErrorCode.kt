package no.orjans.sluice.shared

/**
 * Stable, machine-readable error code. It is part of the API contract (`code` in ProblemDetail)
 * and the key for translated messages (`problem.<code>.title` / `problem.<code>.detail`).
 *
 * Features define their own codes next to the rule they belong to, for example
 * `ErrorCode("job-not-retryable")`.
 */
@JvmInline
value class ErrorCode(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Error code must be lower-case kebab-case, was '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

        val NOT_FOUND = ErrorCode("not-found")
        val VALIDATION_FAILED = ErrorCode("validation-failed")
        val CONFLICT = ErrorCode("conflict")
        val PRECONDITION_FAILED = ErrorCode("precondition-failed")
        val INTERNAL_ERROR = ErrorCode("internal-error")
    }
}
