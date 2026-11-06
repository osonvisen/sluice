package no.orjans.sluice.shared

/**
 * Expected errors. Each variant maps to exactly one HTTP status in `infrastructure.web.ProblemDetailFactory`,
 * so the domain never knows about HTTP.
 *
 * [messageArgs] are inserted into the translated detail text. They must never contain personal data.
 */
sealed interface AppError {
    val code: ErrorCode
    val messageArgs: List<Any>

    /** The resource does not exist (404). */
    data class NotFound(
        val resource: String,
        val id: String,
    ) : AppError {
        override val code = ErrorCode.NOT_FOUND
        override val messageArgs = listOf(resource, id)
    }

    /** The input breaks one or more field rules (400). */
    data class Validation(
        val violations: List<FieldViolation>,
    ) : AppError {
        override val code = ErrorCode.VALIDATION_FAILED
        override val messageArgs = emptyList<Any>()
    }

    /** The resource changed since the client read it, or the request duplicates existing state (409). */
    data class Conflict(
        override val code: ErrorCode = ErrorCode.CONFLICT,
        override val messageArgs: List<Any> = emptyList(),
    ) : AppError

    /** A business rule that needs context rejected the request, e.g. an illegal status transition (422). */
    data class RuleViolation(
        override val code: ErrorCode,
        override val messageArgs: List<Any> = emptyList(),
    ) : AppError
}

data class FieldViolation(
    val field: String,
    val code: ErrorCode,
)
