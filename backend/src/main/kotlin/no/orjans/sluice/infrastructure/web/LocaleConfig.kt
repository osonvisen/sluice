package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.shared.Language
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/**
 * Picks the response language from `Accept-Language`. Unsupported languages fall back to English (ADR 0006).
 * The frontend sends the user's stored choice in that header (ADR 0011), so the server needs no lookup per request.
 */
@Configuration(proxyBeanMethods = false)
class LocaleConfig {
    @Bean
    fun localeResolver(): LocaleResolver =
        AcceptHeaderLocaleResolver().apply {
            supportedLocales = SUPPORTED_LOCALES
            setDefaultLocale(DEFAULT_LOCALE)
        }

    companion object {
        val DEFAULT_LOCALE: Locale = Locale.forLanguageTag(Language.DEFAULT.tag)
        val SUPPORTED_LOCALES: List<Locale> = Language.SUPPORTED.map { Locale.forLanguageTag(it.tag) }
    }
}
