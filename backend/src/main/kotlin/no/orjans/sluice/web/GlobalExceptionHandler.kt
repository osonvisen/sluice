package no.orjans.sluice.web

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.csrf.CsrfException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Turns every exception that reaches the web layer into a ProblemDetail with a `code`.
 *
 * Expected errors from endpoints arrive as [AppErrorException] (see `getOrThrow`).
 * Spring MVC's own exceptions (404, 405, 415, ...) get a code derived from their status.
 * 401 and 403 come from Spring Security, both from its filters (via `ProblemDetailSecurityErrors`)
 * and from `@PreAuthorize` on endpoints.
 * Rejected requests are logged at WARN, unexpected errors at ERROR (security-and-ops standard).
 * Anything unexpected becomes a 500 without internal details; the stack trace is only logged,
 * together with the correlation ID that is also returned to the client.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val problems: ProblemDetailFactory,
) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AppErrorException::class)
    fun handleAppError(ex: AppErrorException): ResponseEntity<ProblemDetail> {
        val problem = problems.from(ex.error)
        log.warn("Request rejected: status={} code={}", problem.status, ex.error.code)
        return ResponseEntity.status(problem.status).body(problem)
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleUnauthenticated(): ResponseEntity<ProblemDetail> = rejected(HttpStatus.UNAUTHORIZED, UNAUTHORIZED)

    /** A missing or stale CSRF token has its own code: reloading fixes it, unlike a missing permission. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ProblemDetail> =
        rejected(HttpStatus.FORBIDDEN, if (ex is CsrfException) CSRF_TOKEN_INVALID else FORBIDDEN)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled exception", ex)
        val problem = problems.create(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val violations =
            ex.bindingResult.fieldErrors.map { FieldViolation(it.field, constraintCode(it.code)) }
        log.warn("Request rejected: status={} code={} fields={}", status.value(), ErrorCode.VALIDATION_FAILED, violations.map { it.field })
        return ResponseEntity.status(status).headers(headers).body(problems.from(AppError.Validation(violations)))
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val code = codeFor(statusCode)
        // Status and code only: never the path or query (may hold personal data) or the body.
        log.warn("Request rejected: status={} code={}", statusCode.value(), code)
        val problem = problems.create(statusCode, code)
        if (problem.detail == null && body is ProblemDetail) problem.detail = body.detail
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun rejected(
        status: HttpStatus,
        code: ErrorCode,
    ): ResponseEntity<ProblemDetail> {
        log.warn("Request rejected: status={} code={}", status.value(), code)
        return ResponseEntity.status(status).body(problems.create(status, code))
    }

    private fun codeFor(status: HttpStatusCode): ErrorCode {
        val name = HttpStatus.resolve(status.value())?.name ?: return ErrorCode("http-${status.value()}")
        return ErrorCode(name.lowercase().replace('_', '-'))
    }

    /** `NotBlank` → `not-blank`, so field errors use the same code style as everything else. */
    private fun constraintCode(constraint: String?): ErrorCode {
        if (constraint.isNullOrBlank()) return INVALID
        return ErrorCode(constraint.replace(CAMEL_CASE_BOUNDARY, "$1-$2").lowercase())
    }

    private companion object {
        val INVALID = ErrorCode("invalid")
        val UNAUTHORIZED = ErrorCode("unauthorized")
        val FORBIDDEN = ErrorCode("forbidden")
        val CSRF_TOKEN_INVALID = ErrorCode("csrf-token-invalid")
        val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
    }
}
