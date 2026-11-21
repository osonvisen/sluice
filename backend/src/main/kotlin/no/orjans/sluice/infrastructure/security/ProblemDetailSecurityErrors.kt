package no.orjans.sluice.infrastructure.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.LocaleResolver

/**
 * Sends 401 and 403 from the security filters through the same `GlobalExceptionHandler` as every other
 * error, so they are ProblemDetails with a `code` and translated text like the rest of the API.
 *
 * The filters run before Spring MVC has picked the request's language, so it is resolved here first.
 */
class ProblemDetailSecurityErrors(
    private val resolver: HandlerExceptionResolver,
    private val locales: LocaleResolver,
) : AuthenticationEntryPoint,
    AccessDeniedHandler {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) = resolve(request, response, authException)

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = resolve(request, response, accessDeniedException)

    private fun resolve(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: Exception,
    ) {
        LocaleContextHolder.setLocale(locales.resolveLocale(request))
        try {
            resolver.resolveException(request, response, null, exception)
        } finally {
            LocaleContextHolder.resetLocaleContext()
        }
    }
}
