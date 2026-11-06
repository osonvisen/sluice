package no.orjans.sluice.shared

/**
 * Outcome of an operation that can fail in an expected way.
 *
 * Expected failures (not found, validation, business rules) are values, not exceptions,
 * so the compiler forces callers to handle them. Translation to HTTP happens in one place
 * (`web.ProblemDetailFactory`).
 */
sealed interface Result<out T> {
    data class Success<out T>(
        val value: T,
    ) : Result<T>

    data class Failure(
        val error: AppError,
    ) : Result<Nothing>

    companion object {
        fun <T> success(value: T): Result<T> = Success(value)

        fun failure(error: AppError): Result<Nothing> = Failure(error)
    }
}

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> =
    when (this) {
        is Result.Success -> Result.Success(transform(value))
        is Result.Failure -> this
    }

inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    when (this) {
        is Result.Success -> transform(value)
        is Result.Failure -> this
    }

inline fun <T, R> Result<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (AppError) -> R,
): R =
    when (this) {
        is Result.Success -> onSuccess(value)
        is Result.Failure -> onFailure(error)
    }
