package no.orjans.sluice.infrastructure.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver
import java.util.Locale

/** Picks the response language from `Accept-Language`. Unsupported languages fall back to English (ADR 0006). */
@Configuration(proxyBeanMethods = false)
class LocaleConfig {
    @Bean
    fun localeResolver(): LocaleResolver =
        AcceptHeaderLocaleResolver().apply {
            supportedLocales = SUPPORTED_LOCALES
            setDefaultLocale(DEFAULT_LOCALE)
        }

    companion object {
        val DEFAULT_LOCALE: Locale = Locale.ENGLISH
        val SUPPORTED_LOCALES: List<Locale> = listOf(Locale.ENGLISH, Locale.forLanguageTag("nb"))
    }
}
