package no.orjans.sluice.web

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component

/**
 * The single place where errors become HTTP responses (RFC 9457 ProblemDetail).
 *
 * Every problem carries a stable `code` that clients can switch on, and a `title`/`detail`
 * translated from `i18n/messages*.properties` for the request's `Accept-Language`.
 */
@Component
class ProblemDetailFactory(
    private val messages: MessageSource,
) {
    fun from(error: AppError): ProblemDetail =
        create(statusOf(error), error.code, error.messageArgs).apply {
            if (error is AppError.Validation) {
                setProperty("violations", error.violations.map { it.toBody() })
            }
        }

    fun create(
        status: HttpStatusCode,
        code: ErrorCode,
        messageArgs: List<Any> = emptyList(),
    ): ProblemDetail {
        val locale = LocaleContextHolder.getLocale()
        val defaultTitle = HttpStatus.resolve(status.value())?.reasonPhrase
        return ProblemDetail.forStatus(status).apply {
            title = messages.getMessage("problem.$code.title", null, defaultTitle, locale)
            detail = messages.getMessage("problem.$code.detail", messageArgs.toTypedArray(), null, locale)
            setProperty(CODE_PROPERTY, code.value)
            CorrelationIdFilter.current()?.let { setProperty(CORRELATION_ID_PROPERTY, it) }
        }
    }

    private fun statusOf(error: AppError): HttpStatus =
        when (error) {
            is AppError.NotFound -> HttpStatus.NOT_FOUND
            is AppError.Validation -> HttpStatus.BAD_REQUEST
            is AppError.Conflict -> HttpStatus.CONFLICT
            is AppError.PreconditionFailed -> HttpStatus.PRECONDITION_FAILED
            is AppError.RuleViolation -> HttpStatus.UNPROCESSABLE_CONTENT
        }

    private fun FieldViolation.toBody(): Map<String, String> {
        val locale = LocaleContextHolder.getLocale()
        val fallback = messages.getMessage(INVALID_FIELD_KEY, null, locale)
        return mapOf(
            "field" to field,
            "code" to code.value,
            "message" to messages.getMessage("validation.$code", null, fallback, locale).orEmpty(),
        )
    }

    companion object {
        const val CODE_PROPERTY = "code"
        const val CORRELATION_ID_PROPERTY = "correlationId"
        private const val INVALID_FIELD_KEY = "validation.invalid"
    }
}
