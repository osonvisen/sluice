package no.orjans.sluice.features.orders.receiveshopifyorder

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.FieldViolation
import no.orjans.sluice.shared.Result

/** The Shopify headers that describe one webhook delivery (shopify.dev: webhooks → delivery structure). */
data class ShopifyDelivery(
    /** Unique per delivery and subscription: stored for tracing, not used for idempotency (see ReceiveShopifyOrderHandler). */
    val webhookId: String,
    val shopDomain: String,
) {
    companion object {
        const val HMAC_HEADER = "X-Shopify-Hmac-Sha256"
        const val WEBHOOK_ID_HEADER = "X-Shopify-Webhook-Id"
        const val SHOP_DOMAIN_HEADER = "X-Shopify-Shop-Domain"
        const val TOPIC_HEADER = "X-Shopify-Topic"

        /** This endpoint only handles new orders. Other topics are a subscription set up wrong. */
        const val SUPPORTED_TOPIC = "orders/create"

        val REQUIRED = ErrorCode("required")
        val UNSUPPORTED_TOPIC = ErrorCode("unsupported-topic")
        val INVALID_SHOP_DOMAIN = ErrorCode("invalid-shop-domain")

        private const val MAX_WEBHOOK_ID_LENGTH = 100
        private val SHOP_DOMAIN = Regex("^[a-z0-9][a-z0-9-]{0,99}\\.myshopify\\.com$")

        fun parse(
            webhookId: String?,
            shopDomain: String?,
            topic: String?,
        ): Result<ShopifyDelivery> {
            val violations =
                buildList {
                    if (webhookId.isNullOrBlank() ||
                        webhookId.length > MAX_WEBHOOK_ID_LENGTH
                    ) {
                        add(FieldViolation(WEBHOOK_ID_HEADER, REQUIRED))
                    }
                    if (shopDomain == null || !SHOP_DOMAIN.matches(shopDomain)) add(FieldViolation(SHOP_DOMAIN_HEADER, INVALID_SHOP_DOMAIN))
                    if (topic != SUPPORTED_TOPIC) add(FieldViolation(TOPIC_HEADER, UNSUPPORTED_TOPIC))
                }
            return if (violations.isEmpty() && webhookId != null && shopDomain != null) {
                Result.success(ShopifyDelivery(webhookId, shopDomain))
            } else {
                Result.failure(AppError.Validation(violations))
            }
        }
    }
}
