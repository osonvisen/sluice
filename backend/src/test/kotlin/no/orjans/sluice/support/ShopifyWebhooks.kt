package no.orjans.sluice.support

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sends Shopify webhooks the way Shopify does: raw JSON body, HMAC over the exact bytes, delivery headers. */
object ShopifyWebhooks {
    const val PATH = "/webhooks/shopify/orders"
    const val SHOP = "sluice-demo.myshopify.com"

    val sampleOrder: ByteArray = checkNotNull(javaClass.getResource("/shopify/orders-create.json")).readBytes()

    /** The sample order with another Shopify order ID, for tests that need several distinct orders. */
    fun sampleOrder(shopifyOrderId: Long): ByteArray =
        String(sampleOrder).replaceFirst("\"id\": 5847329874321", "\"id\": $shopifyOrderId").toByteArray()

    fun sign(
        body: ByteArray,
        secret: String = IntegrationTest.SHOPIFY_SECRET,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(body))
    }

    fun send(
        mvc: MockMvcTester,
        body: ByteArray = sampleOrder,
        webhookId: String = UUID.randomUUID().toString(),
        signature: String? = sign(body),
        topic: String = "orders/create",
        shop: String = SHOP,
    ): MvcTestResult {
        val request =
            mvc
                .post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Shopify-Webhook-Id", webhookId)
                .header("X-Shopify-Topic", topic)
                .header("X-Shopify-Shop-Domain", shop)
        signature?.let { request.header("X-Shopify-Hmac-Sha256", it) }
        return request.exchange()
    }
}
