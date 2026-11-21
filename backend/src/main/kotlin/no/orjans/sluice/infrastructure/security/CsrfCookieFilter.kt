package no.orjans.sluice.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import no.orjans.sluice.web.ApiPaths
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Makes sure the `XSRF-TOKEN` cookie exists before Angular's first POST.
 *
 * Spring Security loads the CSRF token lazily: the cookie is only written once something reads the token.
 * A single-page app never renders a form that would, so this filter reads it on every request.
 * Webhooks are skipped: they are exempt from CSRF, and a server-to-server call has no use for the cookie.
 */
class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        filterChain.doFilter(request, response)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.servletPath.startsWith("${ApiPaths.WEBHOOKS}/")
}
