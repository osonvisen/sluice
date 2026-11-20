package no.orjans.sluice.features.jobs

import no.orjans.sluice.features.jobs.worker.JobWorker
import no.orjans.sluice.support.AngularCsrf
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.FakeErp
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.MutableClock
import no.orjans.sluice.support.ShopifyWebhooks
import no.orjans.sluice.support.TestUsers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.assertj.MockMvcTester

/** The job detail an operator reads before pressing "Run again": payload, attempts and the per-line ERP log. */
@IntegrationTest
class JobDetailIT {
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
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        erp.reset()
        clock.set(MutableClock.START)
    }

    @Test
    fun `an imported order shows its payload, the attempt and what each line sent and got back`() {
        receive(String(ShopifyWebhooks.sampleOrder))
        worker.runOnce()

        val body = assertThat(get(jobId())).hasStatusOk().bodyJson()

        body.extractingPath("$.status").isEqualTo("SUCCEEDED")
        body.extractingPath("$.orderNumber").isEqualTo("#1042")
        body.extractingPath("$.payload.orderId").isEqualTo(orderId())
        body.extractingPath("$.attemptHistory[*].outcome").asArray().containsExactly("SUCCEEDED")
        body.extractingPath("$.attemptHistory[0].finishedAt").isNotNull()
        body.extractingPath("$.lineLog[*].outcome").asArray().containsExactly("ACCEPTED", "ACCEPTED")
        body.extractingPath("$.lineLog[0].sent.sku").isEqualTo("SOCK-WOOL-GREY-3942")
        body.extractingPath("$.lineLog[0].received.erpLineId").isEqualTo("ERP-100001-1")
    }

    @Test
    fun `a rejected order shows why, per line, and nothing about the customer`() {
        receive(String(ShopifyWebhooks.sampleOrder).replace("\"sku\": \"HAT-KNIT-NAVY\",", ""))
        worker.runOnce()

        val result = get(jobId()).exchange()
        val body = assertThat(result).hasStatusOk().bodyJson()

        body.extractingPath("$.status").isEqualTo("DEAD")
        body.extractingPath("$.deadReason").isEqualTo("PERMANENT")
        body.extractingPath("$.attemptHistory[0].outcome").isEqualTo("PERMANENT_FAILURE")
        body.extractingPath("$.lineLog[*].outcome").asArray().containsExactly("INVALID")
        body.extractingPath("$.lineLog[0].errorCode").isEqualTo("MISSING_SKU")
        body.extractingPath("$.lineLog[0].lineNumber").isEqualTo(2)
        body.extractingPath("$.lineLog[0].sent").isNull()
        // The sample order's fictional customer (orders-create.json) must never reach the API.
        assertThat(result.response.contentAsString).doesNotContain("Kari", "Nordmann", "@example", "Eksempelveien")
    }

    @Test
    fun `the history keeps earlier attempts after a manual rerun`() {
        receive(String(ShopifyWebhooks.sampleOrder).replace("\"sku\": \"HAT-KNIT-NAVY\",", ""))
        worker.runOnce()
        val id = jobId()
        val etag = checkNotNull(get(id).exchange().response.getHeader(HttpHeaders.ETAG))
        assertThat(
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, etag)
                .with(TestUsers.operator())
                .with(AngularCsrf.token()),
        ).hasStatusOk()
        worker.runOnce()

        val body = assertThat(get(id)).bodyJson()
        body.extractingPath("$.attempts").isEqualTo(1)
        body.extractingPath("$.attemptHistory[*].outcome").asArray().containsExactly("PERMANENT_FAILURE", "PERMANENT_FAILURE")
        body.extractingPath("$.lineLog[*].attempt").asArray().containsExactly(1, 1)
    }

    @Test
    fun `the detail needs jobs read`() {
        receive(String(ShopifyWebhooks.sampleOrder))

        assertThat(mvc.get().uri("/api/jobs/${jobId()}").with(TestUsers.withoutRoles())).hasStatus(HttpStatus.FORBIDDEN)
    }

    private fun get(id: String) = mvc.get().uri("/api/jobs/$id").with(TestUsers.operator())

    private fun receive(body: String) = assertThat(ShopifyWebhooks.send(mvc, body = body.toByteArray())).hasStatusOk()

    private fun jobId(): String = jdbc.sql("SELECT import_job_id::text FROM orders").query(String::class.java).single()

    private fun orderId(): String = jdbc.sql("SELECT id::text FROM orders").query(String::class.java).single()
}
