package no.orjans.sluice.support

import jakarta.servlet.http.Cookie
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * Sends a CSRF token the way Angular does: the same value in the `XSRF-TOKEN` cookie and the
 * `X-XSRF-TOKEN` header (double submit), checked by the app's real CSRF filter and cookie repository.
 *
 * Use this instead of spring-security-test's `csrf()`. That one swaps the CSRF filter's repository for a
 * session-based one, in the filter chain every test in the context shares, so from then on no response
 * sets the `XSRF-TOKEN` cookie. `ArchitectureTest` forbids it in tests.
 */
object AngularCsrf {
    const val COOKIE = "XSRF-TOKEN"
    const val HEADER = "X-XSRF-TOKEN"
    private const val TOKEN = "test-csrf-token"

    fun token(): RequestPostProcessor =
        RequestPostProcessor { request ->
            request.setCookies(*request.cookies.orEmpty(), Cookie(COOKIE, TOKEN))
            request.addHeader(HEADER, TOKEN)
            request
        }
}
