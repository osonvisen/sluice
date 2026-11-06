package no.orjans.sluice.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake ERP on WireMock that honours the idempotency contract (ADR 0010):
 * - The first request with an `Idempotency-Key` creates an ERP order (201).
 * - Every later request with the same key gets the *same* answer back (200, `Idempotent-Replayed: true`)
 *   and creates nothing.
 * - SKUs starting with `UNKNOWN` are rejected with 422 and line errors. Rejections are not remembered.
 * - Tests can queue faults for the next requests: a 500 (nothing created), or a delay (the order *is*
 *   created, then the answer comes late: "processed, but the response was lost").
 */
class FakeErp : ResponseDefinitionTransformerV2 {
    private val json = JsonMapper.builder().build()
    private val created = ConcurrentHashMap<String, String>()
    private val requestsByKey = ConcurrentHashMap<String, AtomicInteger>()
    private val faults = ConcurrentLinkedQueue<Fault>()
    private val orderNumbers = AtomicInteger(BASE_ORDER_NUMBER)

    @Volatile
    private var requestArrived = CountDownLatch(1)

    override fun getName() = NAME

    override fun applyGlobally() = false

    /** How many ERP orders exist: the number that matters for "never a duplicate ERP order". */
    fun ordersCreated(): Int = created.size

    fun requests(idempotencyKey: String): Int = requestsByKey[idempotencyKey]?.get() ?: 0

    fun failNextWithServerError() = faults.add(Fault.ServerError)

    fun delayNext(delay: Duration) = faults.add(Fault.Delay(delay))

    /** Waits until the ERP has received the next request (e.g. to change the clock while a call is in flight). */
    fun awaitRequest(timeout: Duration): Boolean = requestArrived.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    fun reset() {
        created.clear()
        requestsByKey.clear()
        faults.clear()
        orderNumbers.set(BASE_ORDER_NUMBER)
        requestArrived = CountDownLatch(1)
    }

    override fun transform(serveEvent: ServeEvent): ResponseDefinition {
        val request = serveEvent.request
        val key = request.getHeader(IDEMPOTENCY_KEY)
        if (key != null) {
            requestsByKey.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            requestArrived.countDown()
        }
        val fault = key?.let { faults.poll() }
        return when {
            key == null -> status(400, """{"errors":[{"code":"MISSING_IDEMPOTENCY_KEY","message":"Idempotency-Key required"}]}""")
            fault == Fault.ServerError -> status(500, """{"errors":[{"code":"INTERNAL","message":"ERP down"}]}""")
            else -> createOrReplay(key, json.readTree(request.bodyAsString), fault)
        }
    }

    private fun createOrReplay(
        key: String,
        order: JsonNode,
        fault: Fault?,
    ): ResponseDefinition {
        val unknownSkus = order["lines"].filter { it["sku"].asString().startsWith("UNKNOWN") }
        if (unknownSkus.isNotEmpty() && !created.containsKey(key)) {
            val errors =
                unknownSkus.joinToString(
                    ",",
                ) { """{"lineNumber":${it["lineNumber"].asInt()},"code":"UNKNOWN_SKU","message":"Unknown SKU"}""" }
            return status(422, """{"errors":[$errors]}""")
        }
        var replayed = true
        val body =
            created.computeIfAbsent(key) {
                replayed = false
                newOrder(order)
            }
        val response =
            ResponseDefinitionBuilder
                .responseDefinition()
                .withStatus(if (replayed) 200 else 201)
                .withHeader("Content-Type", "application/json")
                .withHeader("Idempotent-Replayed", replayed.toString())
                .withBody(body)
        if (fault is Fault.Delay) response.withFixedDelay(fault.duration.toMillis().toInt())
        return response.build()
    }

    private fun newOrder(order: JsonNode): String {
        val number = "ERP-${orderNumbers.incrementAndGet()}"
        val lines =
            order["lines"].joinToString(",") {
                """{"lineNumber":${it["lineNumber"].asInt()},"erpLineId":"$number-${it["lineNumber"].asInt()}"}"""
            }
        return """{"orderNumber":"$number","lines":[$lines]}"""
    }

    private fun status(
        code: Int,
        body: String,
    ) = ResponseDefinitionBuilder
        .responseDefinition()
        .withStatus(code)
        .withHeader("Content-Type", "application/json")
        .withBody(body)
        .build()

    private sealed interface Fault {
        data object ServerError : Fault

        data class Delay(
            val duration: Duration,
        ) : Fault
    }

    companion object {
        const val NAME = "fake-erp"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        private const val BASE_ORDER_NUMBER = 100_000
    }
}

/** One WireMock server for the whole test run (like the Postgres container), wired in as `sluice.erp.base-url`. */
@TestConfiguration(proxyBeanMethods = false)
class FakeErpConfiguration {
    @Bean
    fun fakeErp(): FakeErp = FakeErp()

    @Bean(destroyMethod = "stop")
    fun erpServer(fakeErp: FakeErp): WireMockServer =
        WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(fakeErp)).apply {
            start()
            stubFor(WireMock.post("/api/orders").willReturn(WireMock.aResponse().withTransformers(FakeErp.NAME)))
        }

    @Bean
    fun erpBaseUrl(erpServer: WireMockServer) =
        DynamicPropertyRegistrar { registry -> registry.add("sluice.erp.base-url") { erpServer.baseUrl() } }
}
