package no.orjans.sluice.infrastructure.erp

import no.orjans.sluice.features.jobs.JobsProperties
import no.orjans.sluice.features.orders.ErpLineConfirmation
import no.orjans.sluice.features.orders.ErpLineError
import no.orjans.sluice.features.orders.ErpOrder
import no.orjans.sluice.features.orders.ErpOrderSender
import no.orjans.sluice.features.orders.ErpSendResult
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The ERP over HTTP (`POST /api/orders`). Maps the ERP's answers to the port's results:
 * - 2xx → [ErpSendResult.Accepted] (201 first time, 200 when the ERP replays an earlier result)
 * - 408, 409, 429 → [ErpSendResult.Unavailable]: "not now", not "no". 409 can mean the same
 *   idempotency key is still being processed; 429 may carry `Retry-After`, which is passed on.
 * - Other 4xx → [ErpSendResult.Rejected], with the ERP's line errors when it sends them
 * - 5xx, timeout, connection error, unreadable answer → [ErpSendResult.Unavailable]
 */
@Component
@EnableConfigurationProperties(ErpProperties::class)
class HttpErpOrderSender(
    builder: RestClient.Builder,
    properties: ErpProperties,
    jobs: JobsProperties,
    private val clock: Clock,
) : ErpOrderSender {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // ADR 0010: a call should end long before the job's lease, so a slow ERP normally can't cause a takeover.
        val longestCall = properties.connectTimeout.plus(properties.readTimeout)
        require(longestCall.multipliedBy(2) <= jobs.lease) {
            "sluice.erp connect-timeout + read-timeout ($longestCall) must be at most half of sluice.jobs.lease (${jobs.lease})"
        }
    }

    private val client =
        builder
            .baseUrl(properties.baseUrl.toString())
            .requestFactory(
                // HTTP/1.1: the JDK client otherwise tries an h2c upgrade over plain HTTP, which many servers
                // and proxies mishandle (the connection is closed: "EOF reached while reading").
                JdkClientHttpRequestFactory(
                    HttpClient
                        .newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.connectTimeout)
                        .build(),
                ).apply { setReadTimeout(properties.readTimeout) },
            ).build()

    override fun send(order: ErpOrder): ErpSendResult =
        try {
            val response =
                client
                    .post()
                    .uri(ORDERS_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, order.orderReference.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(order.toRequest())
                    .retrieve()
                    .toEntity(ErpOrderResponse::class.java)
            val body = response.body ?: return ErpSendResult.Unavailable("Empty answer from the ERP (HTTP ${response.statusCode.value()})")
            log.info(
                "ERP has the order: orderId={} erpOrderNumber={} status={}",
                order.orderReference,
                body.orderNumber,
                response.statusCode.value(),
            )
            ErpSendResult.Accepted(body.orderNumber, body.lines.map { ErpLineConfirmation(it.lineNumber, it.erpLineId) })
        } catch (ex: HttpClientErrorException) {
            val status = ex.statusCode.value()
            val retryable = RETRYABLE_CLIENT_ERRORS[status]
            if (retryable != null) {
                log.warn("ERP asked us to try again later: orderId={} status={}", order.orderReference, status)
                ErpSendResult.Unavailable("HTTP $status from the ERP ($retryable)", retryAfter(ex))
            } else {
                log.warn("ERP rejected the order: orderId={} status={}", order.orderReference, status)
                ErpSendResult.Rejected(lineErrors(ex))
            }
        } catch (ex: HttpServerErrorException) {
            ErpSendResult.Unavailable("HTTP ${ex.statusCode.value()} from the ERP")
        } catch (ex: ResourceAccessException) {
            log.warn("ERP did not answer: orderId={}", order.orderReference, ex)
            ErpSendResult.Unavailable("No answer from the ERP (timeout or connection error)")
        } catch (ex: RestClientException) {
            // Unreadable answer. The ERP may have the order; the retry with the same key will tell.
            log.warn("Unreadable answer from the ERP: orderId={}", order.orderReference, ex)
            ErpSendResult.Unavailable("Unreadable answer from the ERP")
        }

    /**
     * `Retry-After` on a 429: seconds ("120") or an HTTP date. Anything else is ignored, and the
     * worker's own backoff applies. A date in the past means "now".
     */
    private fun retryAfter(ex: HttpClientErrorException): Duration? {
        val value =
            ex.responseHeaders
                ?.getFirst(HttpHeaders.RETRY_AFTER)
                ?.trim()
                ?.takeIf { ex.statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value() }
                ?: return null
        return value.toLongOrNull()?.let { Duration.ofSeconds(it.coerceAtLeast(0)) }
            ?: httpDateOrNull(value)?.let { Duration.between(clock.instant(), it).coerceAtLeast(Duration.ZERO) }
    }

    private fun httpDateOrNull(value: String): Instant? =
        try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun lineErrors(ex: HttpClientErrorException): List<ErpLineError> {
        val errors =
            try {
                ex.getResponseBodyAs(ErpErrorResponse::class.java)?.errors.orEmpty()
            } catch (_: RestClientException) {
                emptyList()
            }
        return errors
            .ifEmpty { listOf(ErrorBody(null, "HTTP_${ex.statusCode.value()}", "The ERP refused the order")) }
            .map { ErpLineError(it.lineNumber, it.code, it.message) }
    }

    private fun ErpOrder.toRequest() =
        ErpOrderRequest(
            orderReference = orderReference.toString(),
            externalOrderNumber = externalOrderNumber,
            currency = currency.currencyCode,
            orderedAt = orderedAt,
            lines = lines.map { ErpOrderRequestLine(it.lineNumber, it.sku.value, it.quantity.value, it.unitPrice.amount) },
        )

    private companion object {
        const val ORDERS_PATH = "/api/orders"
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

        // Client errors that mean "not now" rather than "no", with the wording shown to operators.
        val RETRYABLE_CLIENT_ERRORS =
            mapOf(
                HttpStatus.REQUEST_TIMEOUT.value() to "request timeout",
                HttpStatus.CONFLICT.value() to "the same order is still being processed",
                HttpStatus.TOO_MANY_REQUESTS.value() to "rate limited",
            )
    }
}

/** The ERP's wire format. Kept here, in the adapter: the rest of Sluice only sees the port's types. */
private data class ErpOrderRequest(
    val orderReference: String,
    val externalOrderNumber: String,
    val currency: String,
    val orderedAt: Instant,
    val lines: List<ErpOrderRequestLine>,
)

private data class ErpOrderRequestLine(
    val lineNumber: Int,
    val sku: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
)

private data class ErpOrderResponse(
    val orderNumber: String,
    val lines: List<ErpOrderResponseLine>,
)

private data class ErpOrderResponseLine(
    val lineNumber: Int,
    val erpLineId: String,
)

private data class ErpErrorResponse(
    val errors: List<ErrorBody>,
)

private data class ErrorBody(
    val lineNumber: Int?,
    val code: String,
    val message: String,
)
