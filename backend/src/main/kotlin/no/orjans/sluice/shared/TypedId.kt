package no.orjans.sluice.shared

import java.util.UUID

/**
 * Base for strongly typed IDs, so an order ID can never be passed where a job ID is expected.
 *
 * Each ID is a `@JvmInline value class` with a private constructor, so it can only be created
 * through its factory functions:
 * ```
 * @JvmInline
 * value class JobId private constructor(override val value: UUID) : TypedId {
 *     companion object {
 *         fun new(): JobId = JobId(UUID.randomUUID())
 *         fun parse(raw: String): Result<JobId> = TypedId.parse(raw, "jobId", ::JobId)
 *     }
 * }
 * ```
 * Spring Data JDBC reads value classes on its own; writing needs `TypedIdWritingConverter`.
 */
interface TypedId {
    val value: UUID

    companion object {
        val INVALID_ID = ErrorCode("invalid-id")

        // Only the canonical 36-character form. UUID.fromString also accepts odd inputs like "1-1-1-1-1".
        private val CANONICAL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Parses untrusted input (path variables, payloads) into an ID, or a validation error for [field]. */
        fun <T : TypedId> parse(
            raw: String,
            field: String,
            create: (UUID) -> T,
        ): Result<T> =
            if (CANONICAL_UUID.matches(raw)) {
                Result.success(create(UUID.fromString(raw)))
            } else {
                Result.failure(AppError.Validation(listOf(FieldViolation(field, INVALID_ID))))
            }
    }
}
