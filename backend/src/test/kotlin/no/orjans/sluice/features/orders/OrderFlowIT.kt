package no.orjans.sluice.features.orders

import no.orjans.sluice.features.jobs.JobContext
import no.orjans.sluice.features.jobs.JobsProperties
import no.orjans.sluice.features.jobs.worker.JobWorker
import no.orjans.sluice.features.jobs.worker.WorkerQueries
import no.orjans.sluice.features.orders.importorder.ImportOrderJobHandler
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.FakeErp
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.MutableClock
import no.orjans.sluice.support.ShopifyWebhooks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Webhook → job → worker → ERP (WireMock) → order and line log, end to end against the real database. */
@IntegrationTest
class OrderFlowIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var worker: JobWorker

    @Autowired
    lateinit var erp: FakeErp

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var properties: JobsProperties

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        erp.reset()
        clock.set(MutableClock.START)
    }

    @Test
    fun `a received order is imported into the erp, with every line logged`() {
        receive()

        worker.runOnce()

        assertThat(state())
            .containsEntry("order_status", "IMPORTED")
            .containsEntry("erp_order_number", "ERP-100001")
            .containsEntry("job_status", "SUCCEEDED")
        assertThat(lineLog()).containsExactly("1 ACCEPTED", "2 ACCEPTED")
        val firstLine =
            jdbc
                .sql(
                    "SELECT sent::text AS sent, received::text AS received FROM job_line_log WHERE line_number = 1",
                ).query()
                .singleRow()
        assertThat(firstLine["sent"].toString()).contains("\"sku\": \"SOCK-WOOL-GREY-3942\"", "\"quantity\": 2", "\"unitPrice\": 149.00")
        assertThat(firstLine["received"].toString()).contains("\"erpLineId\": \"ERP-100001-1\"")
    }

    @Test
    fun `the erp call carries the order id as idempotency key`() {
        receive()

        worker.runOnce()

        assertThat(erp.requests(orderId())).isEqualTo(1)
    }

    @Test
    fun `a missing sku stops the order before the erp, and the line says why`() {
        receive(String(ShopifyWebhooks.sampleOrder).replace("\"sku\": \"HAT-KNIT-NAVY\",", ""))

        worker.runOnce()

        assertThat(state())
            .containsEntry("order_status", "REJECTED")
            .containsEntry("job_status", "DEAD")
            .containsEntry("dead_reason", "PERMANENT")
            .containsEntry("last_error", "Not sent to the ERP: line 2 MISSING_SKU")
        assertThat(lineLog()).containsExactly("2 INVALID MISSING_SKU")
        assertThat(erp.requests(orderId())).describedAs("nothing was sent").isZero()
    }

    @Test
    fun `an sku the erp does not know is a permanent rejection, logged per line`() {
        receive(String(ShopifyWebhooks.sampleOrder).replace("HAT-KNIT-NAVY", "UNKNOWN-HAT"))

        worker.runOnce()
        clock.advance(properties.maxBackoff)
        worker.runOnce()

        assertThat(state())
            .containsEntry("order_status", "REJECTED")
            .containsEntry("job_status", "DEAD")
            .containsEntry("dead_reason", "PERMANENT")
            .containsEntry("last_error", "Rejected by the ERP: line 2 UNKNOWN_SKU")
        assertThat(lineLog()).containsExactly("1 ORDER_REJECTED", "2 REJECTED UNKNOWN_SKU")
        assertThat(erp.requests(orderId())).describedAs("not retried").isEqualTo(1)
    }

    @Test
    fun `an erp server error is temporary, and the retry imports the order once`() {
        receive()
        erp.failNextWithServerError()

        worker.runOnce()
        assertThat(state()).containsEntry("order_status", "RECEIVED").containsEntry("job_status", "FAILED")
        assertThat(lineLog()).containsExactly("1 NO_RESPONSE ERP_UNAVAILABLE", "2 NO_RESPONSE ERP_UNAVAILABLE")

        clock.advance(properties.maxBackoff)
        worker.runOnce()

        assertThat(state()).containsEntry("order_status", "IMPORTED").containsEntry("job_status", "SUCCEEDED")
        assertThat(erp.ordersCreated()).isEqualTo(1)
    }

    @Test
    fun `429 with retry-after waits exactly as long as the erp asked`() {
        receive()
        erp.failNextWith(429, mapOf("Retry-After" to "120"))

        worker.runOnce()
        assertThat(
            state(),
        ).containsEntry("job_status", "FAILED").containsEntry("last_error", "ERP unavailable: HTTP 429 from the ERP (rate limited)")
        assertThat(nextRunAt()).isEqualTo(MutableClock.START.plusSeconds(120))

        clock.advance(Duration.ofSeconds(119))
        assertThat(worker.runOnce()).describedAs("not before Retry-After").isZero()

        clock.advance(Duration.ofSeconds(1))
        worker.runOnce()
        assertThat(state()).containsEntry("order_status", "IMPORTED")
        assertThat(erp.ordersCreated()).isEqualTo(1)
    }

    @Test
    fun `503 with a retry-after longer than max backoff waits only max backoff`() {
        receive()
        erp.failNextWith(
            503,
            mapOf(
                "Retry-After" to
                    properties.maxBackoff
                        .multipliedBy(3)
                        .seconds
                        .toString(),
            ),
        )

        worker.runOnce()

        assertThat(state()).containsEntry("job_status", "FAILED").containsEntry("last_error", "ERP unavailable: HTTP 503 from the ERP")
        assertThat(nextRunAt()).isEqualTo(MutableClock.START.plus(properties.maxBackoff))

        clock.advance(properties.maxBackoff)
        worker.runOnce()
        assertThat(state()).containsEntry("order_status", "IMPORTED")
        assertThat(erp.ordersCreated()).isEqualTo(1)
    }

    @Test
    fun `409 from the erp is temporary, and the retry imports the order once`() {
        receive()
        erp.failNextWith(409)

        worker.runOnce()
        assertThat(state())
            .containsEntry("order_status", "RECEIVED")
            .containsEntry("job_status", "FAILED")
            .containsEntry("last_error", "ERP unavailable: HTTP 409 from the ERP (the same order is still being processed)")

        clock.advance(properties.maxBackoff)
        worker.runOnce()
        assertThat(state()).containsEntry("order_status", "IMPORTED").containsEntry("job_status", "SUCCEEDED")
        assertThat(erp.ordersCreated()).isEqualTo(1)
    }

    @Test
    fun `408 from the erp is temporary, not a rejection`() {
        receive()
        erp.failNextWith(408)

        worker.runOnce()

        assertThat(
            state(),
        ).containsEntry("order_status", "RECEIVED").containsEntry("job_status", "FAILED").containsEntry("dead_reason", null)
        assertThat(lineLog()).containsExactly("1 NO_RESPONSE ERP_UNAVAILABLE", "2 NO_RESPONSE ERP_UNAVAILABLE")
    }

    @Test
    fun `a timeout after the erp processed the order does not create a second erp order`() {
        receive()
        erp.delayNext(Duration.ofSeconds(3))

        worker.runOnce()
        assertThat(state()).containsEntry("job_status", "FAILED")

        clock.advance(properties.maxBackoff)
        worker.runOnce()

        assertThat(state()).containsEntry("order_status", "IMPORTED").containsEntry("erp_order_number", "ERP-100001")
        assertThat(erp.requests(orderId())).isEqualTo(2)
        assertThat(erp.ordersCreated()).describedAs("the retry got the replayed answer").isEqualTo(1)
    }

    @Test
    fun `a crash after the erp said yes but before our commit does not create a second erp order`(
        @Autowired queries: WorkerQueries,
        @Autowired importHandler: ImportOrderJobHandler,
        @Autowired transactions: TransactionTemplate,
    ) {
        receive()
        // A worker claims the job and the ERP creates the order ...
        val job = transactions.execute { queries.claimDue(clock.instant(), clock.instant().plus(properties.lease), 1) }.single()
        importHandler.handle(ImportOrderPayload(UUID.fromString(orderId())), JobContext(job.id, job.attempt, job.attemptRowId))
        // ... and then the process dies: the outcome and its effects are never committed.
        assertThat(state()).containsEntry("order_status", "RECEIVED").containsEntry("job_status", "RUNNING")

        clock.advance(properties.lease.plusSeconds(1))
        worker.runOnce()
        clock.advance(properties.maxBackoff)
        worker.runOnce()

        assertThat(state()).containsEntry("order_status", "IMPORTED").containsEntry("erp_order_number", "ERP-100001")
        assertThat(erp.ordersCreated()).isEqualTo(1)
        assertThat(attempts()).containsExactly("LEASE_EXPIRED", "SUCCEEDED")
    }

    @Test
    fun `a lease that expires during the erp call discards the slow result, and the erp has one order`() {
        receive()
        erp.delayNext(Duration.ofMillis(1500))
        val slowWorker = Executors.newSingleThreadExecutor().submit<Int> { worker.runOnce() }
        assertThat(erp.awaitRequest(Duration.ofSeconds(10))).isTrue()

        // While the first call is in flight: the lease runs out, another poll takes the job over and finishes it.
        clock.advance(properties.lease.plusSeconds(1))
        worker.runOnce()
        clock.advance(properties.maxBackoff)
        worker.runOnce()
        assertThat(state()).containsEntry("job_status", "SUCCEEDED")

        slowWorker.get(10, TimeUnit.SECONDS)

        assertThat(state()).containsEntry("order_status", "IMPORTED").containsEntry("job_status", "SUCCEEDED")
        assertThat(erp.requests(orderId())).isEqualTo(2)
        assertThat(erp.ordersCreated()).isEqualTo(1)
        assertThat(attempts()).containsExactly("LEASE_EXPIRED", "SUCCEEDED")
        assertThat(lineLog()).describedAs("only the owner's attempt is logged").containsExactly("1 ACCEPTED", "2 ACCEPTED")
    }

    @Test
    fun `running the import again for an imported order changes nothing and calls no erp`() {
        receive()
        worker.runOnce()
        jdbc.sql("UPDATE jobs SET status = 'PENDING'").update()

        worker.runOnce()

        assertThat(state()).containsEntry("order_status", "IMPORTED").containsEntry("job_status", "SUCCEEDED")
        assertThat(erp.requests(orderId())).isEqualTo(1)
    }

    private fun receive(body: String = String(ShopifyWebhooks.sampleOrder)) {
        assertThat(ShopifyWebhooks.send(mvc, body = body.toByteArray())).hasStatusOk()
    }

    private fun orderId(): String = jdbc.sql("SELECT id::text FROM orders").query(String::class.java).single()

    private fun state(): Map<String, Any?> =
        jdbc
            .sql(
                """
                SELECT o.status AS order_status, o.erp_order_number, j.status AS job_status, j.dead_reason, j.last_error
                FROM orders o JOIN jobs j ON j.id = o.import_job_id
                """.trimIndent(),
            ).query()
            .singleRow()

    private fun nextRunAt(): Instant =
        jdbc
            .sql("SELECT j.next_run_at FROM jobs j JOIN orders o ON o.import_job_id = j.id")
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()

    private fun attempts(): List<String?> =
        jdbc
            .sql("SELECT a.outcome FROM job_attempts a JOIN orders o ON o.import_job_id = a.job_id ORDER BY a.id")
            .query(String::class.java)
            .list()

    private fun lineLog(): List<String> =
        jdbc
            .sql("SELECT concat_ws(' ', line_number, outcome, error_code) FROM job_line_log ORDER BY id")
            .query(String::class.java)
            .list()
            .map { it.orEmpty() }
}
