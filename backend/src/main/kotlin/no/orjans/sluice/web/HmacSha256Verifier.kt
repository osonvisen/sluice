package no.orjans.sluice.web

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks a base64 HMAC-SHA256 signature of a raw request body, as sent by Shopify in
 * `X-Shopify-Hmac-Sha256`. Run it on the raw bytes *before* parsing JSON: re-serialised JSON
 * would not give the same bytes, and unauthenticated input should not reach the parser.
 */
@Component
class HmacSha256Verifier {
    fun isValid(
        body: ByteArray,
        signatureBase64: String?,
        secret: String,
    ): Boolean {
        val provided = signatureBase64?.trim()?.takeIf { it.isNotEmpty() }?.let(::decodeOrNull) ?: return false
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)) }
        // Constant-time comparison, so response time does not leak how many bytes matched.
        return MessageDigest.isEqual(mac.doFinal(body), provided)
    }

    private fun decodeOrNull(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
