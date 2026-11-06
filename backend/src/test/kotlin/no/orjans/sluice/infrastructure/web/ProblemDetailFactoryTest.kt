package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import java.util.Locale

class ProblemDetailFactoryTest {
    private val messages =
        ResourceBundleMessageSource().apply {
            setBasename("i18n/messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
        }
    private val factory = ProblemDetailFactory(messages)

    // Pin English so the tests do not depend on the machine's locale (Norwegian on a dev Mac).
    @BeforeEach
    fun englishByDefault() = LocaleContextHolder.setLocale(Locale.ENGLISH)

    @AfterEach
    fun resetLocale() = LocaleContextHolder.resetLocaleContext()

    @Test
    fun `each app error maps to its status`() {
        assertThat(factory.from(AppError.NotFound("job", "1")).status).isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(factory.from(AppError.Validation(emptyList())).status).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(factory.from(AppError.Conflict()).status).isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(factory.from(AppError.RuleViolation(ErrorCode("job-not-retryable"))).status)
            .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value())
    }

    @Test
    fun `problem carries the error code`() {
        val problem = factory.from(AppError.RuleViolation(ErrorCode("job-not-retryable")))

        assertThat(problem.properties).containsEntry("code", "job-not-retryable")
    }

    @Test
    fun `title is translated for the current locale`() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("nb"))

        assertThat(factory.from(AppError.Conflict()).title).isEqualTo("Konflikt")
    }

    @Test
    fun `code without translation falls back to the http reason phrase`() {
        val problem = factory.from(AppError.RuleViolation(ErrorCode("untranslated-rule")))

        assertThat(problem.title).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.reasonPhrase)
        assertThat(problem.detail).isNull()
    }

    @Test
    fun `validation problem lists violations with translated messages`() {
        val problem = factory.from(AppError.Validation(listOf(FieldViolation("sku", ErrorCode("not-blank")))))

        assertThat(problem.properties?.get("violations"))
            .isEqualTo(listOf(mapOf("field" to "sku", "code" to "not-blank", "message" to "Must not be empty.")))
    }

    @Test
    fun `unknown violation code gets the generic invalid message`() {
        val problem = factory.from(AppError.Validation(listOf(FieldViolation("gtin", ErrorCode("gtin-check-digit")))))

        assertThat(problem.properties?.get("violations"))
            .isEqualTo(listOf(mapOf("field" to "gtin", "code" to "gtin-check-digit", "message" to "Invalid value.")))
    }
}
