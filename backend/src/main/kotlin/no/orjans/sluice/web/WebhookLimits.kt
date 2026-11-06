package no.orjans.sluice.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.util.unit.DataSize
import org.springframework.web.ErrorResponseException
import org.springframework.web.servlet.HandlerInterceptor

@ConfigurationProperties("sluice.webhooks")
data class WebhookProperties(
    /** Largest accepted webhook body. A Shopify order with many lines is well below 100 KB. */
    val maxBodySize: DataSize = DataSize.ofKilobytes(DEFAULT_MAX_BODY_KB),
) {
    private companion object {
        const val DEFAULT_MAX_BODY_KB = 512L
    }
}

/**
 * Rejects oversized webhook bodies before anything reads them. Runs before Spring reads the
 * `@RequestBody`, so a large body is never buffered.
 * - No `Content-Length` (chunked upload) → 411: the size must be known up front.
 * - Larger than [WebhookProperties.maxBodySize] → 413.
 */
class WebhookSizeLimitInterceptor(
    private val properties: WebhookProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val length = request.contentLengthLong
        if (length < 0) throw ErrorResponseException(HttpStatus.LENGTH_REQUIRED)
        if (length > properties.maxBodySize.toBytes()) throw ErrorResponseException(HttpStatus.CONTENT_TOO_LARGE)
        return true
    }
}
