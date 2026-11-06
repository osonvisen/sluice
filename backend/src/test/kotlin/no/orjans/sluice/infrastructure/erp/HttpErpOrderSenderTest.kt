package no.orjans.sluice.infrastructure.erp

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import no.orjans.sluice.features.jobs.JobsProperties
import no.orjans.sluice.features.orders.ErpLineConfirmation
import no.orjans.sluice.features.orders.ErpLineError
import no.orjans.sluice.features.orders.ErpOrder
import no.orjans.sluice.features.orders.ErpOrderLine
import no.orjans.sluice.features.orders.ErpSendResult
import no.orjans.sluice.features.orders.OrderId
import no.orjans.sluice.shared.Money
import no.orjans.sluice.shared.Quantity
import no.orjans.sluice.shared.Sku
import no.orjans.sluice.shared.valueOrNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.ServerSocket
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

/** The adapter's contract with the ERP's wire format, against a plain WireMock (no Spring context). */
class HttpErpOrderSenderTest {
    private val server = WireMockServer(options().dynamicPort())
    private val clock = Clock.fixed(Instant.parse("2026-11-06T09:00:00Z"), ZoneOffset.UTC)
    private val orderId = OrderId.of(UUID.fromString("0b8f3c1e-6c1a-4b7e-9d65-1f2a3b4c5d6e"))

    private val order =
        ErpOrder(
            orderReference = orderId,
            externalOrderNumber = "#1042",
            currency = Currency.getInstance("NOK"),
            orderedAt = Instant.parse("2026-11-05T13:32:11Z"),
            lines =
                listOf(
                    ErpOrderLine(
                        1,
                        checkNotNull(Sku.parse("SOCK").valueOrNull()),
                        checkNotNull(Quantity.of(2).valueOrNull()),
                        checkNotNull(Money.of(BigDecimal("149.00"), "NOK").valueOrNull()),
                    ),
                ),
        )

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.stop()

    private fun sender(
        baseUrl: String = server.baseUrl(),
        readTimeout: Duration = Duration.ofSeconds(1),
        lease: Duration = Duration.ofMinutes(5),
    ) = HttpErpOrderSender(
        RestClient.builder(),
        ErpProperties(URI(baseUrl), Duration.ofSeconds(1), readTimeout),
        JobsProperties(5, Duration.ofSeconds(30), Duration.ofHours(1), 0.2, lease, 10, JobsProperties.Worker(false, Duration.ofSeconds(2))),
        clock,
    )

    private fun stub(
        status: Int,
        body: String,
    ) = server.stubFor(
        post("/api/orders").willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)),
    )

    private fun stubWithHeader(
        status: Int,
        header: String,
        value: String,
    ) = server.stubFor(post("/api/orders").willReturn(aResponse().withStatus(status).withHeader(header, value).withBody("{}")))

    @Test
    fun `posts the order in the erp's format with the order id as idempotency key`() {
        stub(201, """{"orderNumber":"ERP-1","lines":[{"lineNumber":1,"erpLineId":"ERP-1-1"}]}""")

        sender().send(order)

        server.verify(
            postRequestedFor(urlEqualTo("/api/orders"))
                .withHeader("Idempotency-Key", equalTo(orderId.toString()))
                .withRequestBody(
                    equalToJson(
                        """
                        {"orderReference":"$orderId","externalOrderNumber":"#1042","currency":"NOK",
                         "orderedAt":"2026-11-05T13:32:11Z",
                         "lines":[{"lineNumber":1,"sku":"SOCK","quantity":2,"unitPrice":149.00}]}
                        """,
                    ),
                ),
        )
    }

    @Test
    fun `201 and a replayed 200 are both accepted`() {
        val expected = ErpSendResult.Accepted("ERP-1", listOf(ErpLineConfirmation(1, "ERP-1-1")))
        stub(201, """{"orderNumber":"ERP-1","lines":[{"lineNumber":1,"erpLineId":"ERP-1-1"}]}""")
        assertThat(sender().send(order)).isEqualTo(expected)

        stub(200, """{"orderNumber":"ERP-1","lines":[{"lineNumber":1,"erpLineId":"ERP-1-1"}]}""")
        assertThat(sender().send(order)).isEqualTo(expected)
    }

    @Test
    fun `422 is a rejection with the erp's line errors`() {
        stub(422, """{"errors":[{"lineNumber":1,"code":"UNKNOWN_SKU","message":"Unknown SKU"}]}""")

        assertThat(sender().send(order)).isEqualTo(ErpSendResult.Rejected(listOf(ErpLineError(1, "UNKNOWN_SKU", "Unknown SKU"))))
    }

    @Test
    fun `a 4xx without an error body is still a rejection, with the status as code`() {
        stub(400, "not json")

        assertThat(
            sender().send(order),
        ).isEqualTo(ErpSendResult.Rejected(listOf(ErpLineError(null, "HTTP_400", "The ERP refused the order"))))
    }

    @Test
    fun `408 request timeout is temporary, not a rejection`() {
        stub(408, "{}")

        assertThat(sender().send(order)).isEqualTo(ErpSendResult.Unavailable("HTTP 408 from the ERP (request timeout)"))
    }

    @Test
    fun `409 is temporary, because the same idempotency key may still be in progress`() {
        stub(409, """{"errors":[{"code":"IN_PROGRESS","message":"Same key is being processed"}]}""")

        assertThat(sender().send(order))
            .isEqualTo(ErpSendResult.Unavailable("HTTP 409 from the ERP (the same order is still being processed)"))
    }

    @Test
    fun `429 with retry-after in seconds passes the wait on`() {
        stubWithHeader(429, "Retry-After", "120")

        assertThat(
            sender().send(order),
        ).isEqualTo(ErpSendResult.Unavailable("HTTP 429 from the ERP (rate limited)", Duration.ofSeconds(120)))
    }

    @Test
    fun `429 with retry-after as an http date is measured from now`() {
        stubWithHeader(429, "Retry-After", "Fri, 06 Nov 2026 09:05:00 GMT")

        assertThat((sender().send(order) as ErpSendResult.Unavailable).retryAfter).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `429 with a retry-after date in the past means now`() {
        stubWithHeader(429, "Retry-After", "Fri, 06 Nov 2026 08:00:00 GMT")

        assertThat((sender().send(order) as ErpSendResult.Unavailable).retryAfter).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `429 without a usable retry-after leaves the wait to our backoff`() {
        stub(429, "{}")
        assertThat((sender().send(order) as ErpSendResult.Unavailable).retryAfter).isNull()

        stubWithHeader(429, "Retry-After", "soon")
        assertThat((sender().send(order) as ErpSendResult.Unavailable).retryAfter).isNull()
    }

    @Test
    fun `retry-after is only read on 429`() {
        stubWithHeader(503, "Retry-After", "120")

        assertThat((sender().send(order) as ErpSendResult.Unavailable).retryAfter).isNull()
    }

    @Test
    fun `5xx is unavailable`() {
        stub(503, "{}")

        assertThat(sender().send(order)).isEqualTo(ErpSendResult.Unavailable("HTTP 503 from the ERP"))
    }

    @Test
    fun `a slow answer is unavailable after the read timeout`() {
        server.stubFor(post("/api/orders").willReturn(aResponse().withStatus(201).withFixedDelay(1500)))

        assertThat(sender(readTimeout = Duration.ofMillis(300)).send(order)).isInstanceOf(ErpSendResult.Unavailable::class.java)
    }

    @Test
    fun `an erp that is down is unavailable`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertThat(sender(baseUrl = "http://localhost:$closedPort").send(order)).isInstanceOf(ErpSendResult.Unavailable::class.java)
    }

    @Test
    fun `an unreadable success answer is unavailable, so the retry asks again`() {
        stub(201, """{"unexpected":true}""")

        assertThat(sender().send(order)).isEqualTo(ErpSendResult.Unavailable("Unreadable answer from the ERP"))
    }

    @Test
    fun `refuses to start when a call could outlast the job lease`() {
        assertThatThrownBy { sender(readTimeout = Duration.ofMinutes(3), lease = Duration.ofMinutes(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sluice.jobs.lease")
    }
}
