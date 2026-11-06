package no.orjans.sluice.web

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.Result

/**
 * Unwraps a use case's [Result] in an endpoint (ADR 0008).
 *
 * ```
 * @GetMapping("/jobs/{id}")
 * fun get(@PathVariable id: String): JobResponse = getJob.handle(id).getOrThrow().toResponse()
 * ```
 * A failure becomes [AppErrorException], which [GlobalExceptionHandler] turns into a ProblemDetail
 * through [ProblemDetailFactory]. The endpoint keeps its real return type, so the OpenAPI schema is right.
 */
fun <T> Result<T>.getOrThrow(): T =
    when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AppErrorException(error)
    }

/**
 * Carries an expected error from an endpoint to [GlobalExceptionHandler]. Only thrown by [getOrThrow];
 * handlers and the domain return [Result] instead. No stack trace: it is not a bug.
 */
class AppErrorException(
    val error: AppError,
) : RuntimeException(error.code.value, null, false, false)
