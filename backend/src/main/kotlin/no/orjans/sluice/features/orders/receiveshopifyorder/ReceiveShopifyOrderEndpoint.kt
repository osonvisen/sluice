package no.orjans.sluice.features.orders.receiveshopifyorder

import io.swagger.v3.oas.annotations.Hidden
import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.HmacSha256Verifier
import no.orjans.sluice.web.getOrThrow
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Shopify `orders/create` webhook. Order of checks, cheapest and safest first:
 * 1. Size (WebhookSizeLimitInterceptor, before the body is read): 411 / 413.
 * 2. HMAC over the raw bytes, before any JSON parsing: 401.
 * 3. Headers and payload: 400 with every problem listed.
 * 4. Store order + job in one transaction: 200, also for a duplicate (anything else makes Shopify retry).
 *
 * Not in the OpenAPI spec: it is called by Shopify, not by our frontend.
 *
 * Only in a web application: the `migrate` step runs without a web server and must not need the
 * Shopify secret, which [ShopifyProperties] requires.
 */
@Hidden
@RestController
@ConditionalOnWebApplication
@EnableConfigurationProperties(ShopifyProperties::class)
class ReceiveShopifyOrderEndpoint(
    private val verifier: HmacSha256Verifier,
    private val properties: ShopifyProperties,
    private val parser: ShopifyOrderParser,
    private val handler: ReceiveShopifyOrderHandler,
) {
    @PostMapping("${ApiPaths.WEBHOOKS}/shopify/orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestBody body: ByteArray,
        @RequestHeader(ShopifyDelivery.HMAC_HEADER, required = false) hmac: String?,
        @RequestHeader(ShopifyDelivery.WEBHOOK_ID_HEADER, required = false) webhookId: String?,
        @RequestHeader(ShopifyDelivery.SHOP_DOMAIN_HEADER, required = false) shopDomain: String?,
        @RequestHeader(ShopifyDelivery.TOPIC_HEADER, required = false) topic: String?,
    ): ResponseEntity<Void> {
        if (!verifier.isValid(body, hmac, properties.webhookSecret)) throw ErrorResponseException(HttpStatus.UNAUTHORIZED)
        val delivery = ShopifyDelivery.parse(webhookId, shopDomain, topic).getOrThrow()
        val order = parser.parse(body).getOrThrow()
        handler.handle(delivery, order)
        return ResponseEntity.ok().build()
    }
}
