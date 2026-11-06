package no.orjans.sluice.features.orders.receiveshopifyorder

import no.orjans.sluice.features.jobs.EnqueueResult
import no.orjans.sluice.features.jobs.IdempotencyKey
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.features.jobs.JobQueue
import no.orjans.sluice.features.orders.ImportOrderPayload
import no.orjans.sluice.features.orders.OrderId
import no.orjans.sluice.features.orders.OrderImport
import no.orjans.sluice.features.orders.OrderStatus
import no.orjans.sluice.shared.atUtc
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

sealed interface ReceiveResult {
    data class Received(
        val orderId: OrderId,
        val jobId: JobId,
    ) : ReceiveResult

    /** The same order arrived before (a redelivery, or another subscription). Nothing was stored. */
    data class Duplicate(
        val jobId: JobId,
    ) : ReceiveResult
}

/**
 * Stores the order and its import job in one transaction (ADR 0002), and nothing else: the import
 * itself runs later on the queue, so Shopify gets its answer well inside its 5-second timeout.
 *
 * **Idempotency key: shop + Shopify order ID**, not `X-Shopify-Webhook-Id`. Shopify documents the
 * webhook ID as unique per delivery *and per subscription*, so the same order can arrive with
 * different webhook IDs (e.g. two subscriptions, or a resend). Importing an order twice would
 * create two ERP orders, so the business key decides. The job's unique `idempotency_key` is the
 * final guard under parallel deliveries.
 */
@Component
class ReceiveShopifyOrderHandler(
    private val jobQueue: JobQueue,
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(
        delivery: ShopifyDelivery,
        order: IncomingOrder,
    ): ReceiveResult {
        val orderId = OrderId.new()
        val key = IdempotencyKey("shopify-order:${delivery.shopDomain}:${order.externalOrderId}")
        return when (val enqueued = jobQueue.enqueue(OrderImport.JOB_TYPE, ImportOrderPayload(orderId.value), key)) {
            is EnqueueResult.AlreadyEnqueued -> {
                log.info("Duplicate order delivery ignored: shopifyOrderId={} jobId={}", order.externalOrderId, enqueued.jobId)
                ReceiveResult.Duplicate(enqueued.jobId)
            }

            is EnqueueResult.Enqueued -> {
                insertOrder(orderId, enqueued.jobId, delivery, order)
                log.info("Order received: orderId={} shopifyOrderId={} jobId={}", orderId, order.externalOrderId, enqueued.jobId)
                ReceiveResult.Received(orderId, enqueued.jobId)
            }
        }
    }

    private fun insertOrder(
        orderId: OrderId,
        jobId: JobId,
        delivery: ShopifyDelivery,
        order: IncomingOrder,
    ) {
        val now = clock.instant().atUtc()
        jdbc
            .sql(
                """
                INSERT INTO orders (id, channel, shop_domain, external_order_id, order_number, currency, ordered_at,
                                    status, webhook_id, import_job_id, received_at, updated_at)
                VALUES (:id, 'SHOPIFY', :shop, :externalId, :number, :currency, :orderedAt,
                        :status, :webhookId, :jobId, :now, :now)
                """.trimIndent(),
            ).param("id", orderId.value)
            .param("shop", delivery.shopDomain)
            .param("externalId", order.externalOrderId)
            .param("number", order.orderNumber)
            .param("currency", order.currency.currencyCode)
            .param("orderedAt", order.orderedAt.atUtc())
            .param("status", OrderStatus.RECEIVED.name)
            .param("webhookId", delivery.webhookId)
            .param("jobId", jobId.value)
            .param("now", now)
            .update()
        order.lines.forEachIndexed { index, line ->
            jdbc
                .sql(
                    """
                    INSERT INTO order_lines (order_id, line_number, external_line_id, sku, title, quantity, unit_price)
                    VALUES (:orderId, :lineNumber, :externalLineId, :sku, :title, :quantity, :unitPrice)
                    """.trimIndent(),
                ).param("orderId", orderId.value)
                .param("lineNumber", index + 1)
                .param("externalLineId", line.externalLineId)
                .param("sku", line.sku)
                .param("title", line.title)
                .param("quantity", line.quantity.value)
                .param("unitPrice", line.unitPrice.amount)
                .update()
        }
    }
}
