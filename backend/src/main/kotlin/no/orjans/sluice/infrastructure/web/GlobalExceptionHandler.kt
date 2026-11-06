package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Turns every exception that reaches the web layer into a ProblemDetail with a `code`.
 *
 * Spring MVC's own exceptions (404, 405, 415, ...) get a code derived from their status.
 * Rejected requests are logged at WARN, unexpected errors at ERROR (security-and-ops standard).
 * Anything unexpected becomes a 500 without internal details; the stack trace is only logged,
 * together with the correlation ID that is also returned to the client.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val problems: ProblemDetailFactory,
) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
    }
}
