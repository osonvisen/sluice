package no.orjans.sluice.infrastructure.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Gives every request a correlation ID: reused from the `X-Correlation-Id` header when it is
 * well-formed, otherwise generated. It is put in the logging MDC and echoed in the response,
 * so an error reported by a user can be found in the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER)?.takeIf { VALID.matches(it) } ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"

        // Untrusted input ends up in logs, so only allow a short, safe character set.
        private val VALID = Regex("^[A-Za-z0-9-]{1,64}$")

        fun current(): String? = MDC.get(MDC_KEY)
    }
}
