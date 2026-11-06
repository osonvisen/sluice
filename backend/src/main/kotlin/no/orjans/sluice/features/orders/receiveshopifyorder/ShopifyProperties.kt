package no.orjans.sluice.features.orders.receiveshopifyorder

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("sluice.shopify")
data class ShopifyProperties(
    /** The Shopify app's client secret. Signs every webhook (X-Shopify-Hmac-Sha256). */
    val webhookSecret: String,
) {
    init {
        // Fail at startup, not on the first webhook: without a secret every delivery would be rejected.
        require(webhookSecret.isNotBlank()) { "sluice.shopify.webhook-secret (SLUICE_SHOPIFY_WEBHOOK_SECRET) must be set" }
    }

    // Never print the secret, e.g. when Spring logs a failed binding.
    override fun toString(): String = "ShopifyProperties(webhookSecret=****)"
}
