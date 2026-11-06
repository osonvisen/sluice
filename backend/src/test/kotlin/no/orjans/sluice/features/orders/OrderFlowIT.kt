package no.orjans.sluice.features.orders

import no.orjans.sluice.features.jobs.worker.JobWorker
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.MutableClock
import no.orjans.sluice.support.ShopifyWebhooks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.assertj.MockMvcTester

/** Webhook → job → worker → order, end to end against the real database. */
@IntegrationTest
class OrderFlowIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var worker: JobWorker

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        clock.set(MutableClock.START)
    }

    @Test
    fun `a received order is accepted by its import job`() {
        assertThat(ShopifyWebhooks.send(mvc)).hasStatusOk()

        worker.runOnce()

        assertThat(state()).containsEntry("order_status", "ACCEPTED").containsEntry("job_status", "SUCCEEDED")
    }

    @Test
    fun `an order with a missing sku is stored, and its job dies permanently naming the line`() {
        val withoutSku = String(ShopifyWebhooks.sampleOrder).replace("\"sku\": \"HAT-KNIT-NAVY\",", "").toByteArray()
        assertThat(ShopifyWebhooks.send(mvc, body = withoutSku)).hasStatusOk()

        worker.runOnce()

        assertThat(state())
            .containsEntry("order_status", "RECEIVED")
            .containsEntry("job_status", "DEAD")
            .containsEntry("dead_reason", "PERMANENT")
            .containsEntry("last_error", "Missing or invalid SKU on line 2")
    }

    @Test
    fun `running the import twice is harmless`() {
        ShopifyWebhooks.send(mvc)
        worker.runOnce()
        jdbc.sql("UPDATE jobs SET status = 'PENDING'").update()

        worker.runOnce()

        assertThat(state()).containsEntry("order_status", "ACCEPTED").containsEntry("job_status", "SUCCEEDED")
    }

    private fun state(): Map<String, Any?> =
        jdbc
            .sql(
                """
                SELECT o.status AS order_status, j.status AS job_status, j.dead_reason, j.last_error
                FROM orders o JOIN jobs j ON j.id = o.import_job_id
                """.trimIndent(),
            ).query()
            .singleRow()
}
