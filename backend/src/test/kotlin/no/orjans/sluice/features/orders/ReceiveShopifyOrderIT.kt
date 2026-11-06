package no.orjans.sluice.features.orders

import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.ShopifyWebhooks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class ReceiveShopifyOrderIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun clean() = cleaner.clean()

    @Test
    fun `a signed order is stored with its lines and an import job`() {
        assertThat(ShopifyWebhooks.send(mvc, webhookId = "delivery-1")).hasStatusOk()

        val order =
            jdbc
                .sql("SELECT order_number, status, webhook_id, import_job_id::text AS job FROM orders")
                .query()
                .singleRow()
        assertThat(
            order,
        ).containsEntry("order_number", "#1042").containsEntry("status", "RECEIVED").containsEntry("webhook_id", "delivery-1")
        assertThat(count("order_lines")).isEqualTo(2)
        val job = jdbc.sql("SELECT id::text AS id, type, status FROM jobs").query().singleRow()
        assertThat(job).containsEntry("id", order["job"]).containsEntry("type", "order.import").containsEntry("status", "PENDING")
    }

    @Test
    fun `no customer data is stored anywhere`() {
        ShopifyWebhooks.send(mvc)

        val everything =
            jdbc
                .sql(
                    """
                    SELECT row_to_json(o)::text FROM orders o
                    UNION ALL SELECT row_to_json(l)::text FROM order_lines l
                    UNION ALL SELECT payload::text FROM jobs
                    """.trimIndent(),
                ).query(String::class.java)
                .list()
                .joinToString()
        assertThat(everything).doesNotContain("Nordmann", "example.com", "Eksempelveien", "4799999999", "203.0.113.17")
    }

    @Test
    fun `the same delivery twice creates one order and one job, and both get 200`() {
        assertThat(ShopifyWebhooks.send(mvc, webhookId = "delivery-1")).hasStatusOk()
        assertThat(ShopifyWebhooks.send(mvc, webhookId = "delivery-1")).hasStatusOk()

        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("jobs")).isEqualTo(1)
    }

    @Test
    fun `the same order with another webhook id is still a duplicate`() {
        ShopifyWebhooks.send(mvc, webhookId = "subscription-a")
        ShopifyWebhooks.send(mvc, webhookId = "subscription-b")

        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("jobs")).isEqualTo(1)
    }

    @Test
    fun `parallel deliveries of the same order create exactly one order and one job`() {
        val deliveries = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(deliveries)
        val statuses =
            (1..deliveries).map { n ->
                pool.submit<Int> {
                    start.await()
                    ShopifyWebhooks.send(mvc, webhookId = "parallel-$n").response.status
                }
            }
        start.countDown()
        val results = statuses.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(results).containsOnly(HttpStatus.OK.value())
        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("jobs")).isEqualTo(1)
    }

    @Test
    fun `a wrong signature is 401 and nothing is stored`() {
        val result = ShopifyWebhooks.send(mvc, signature = ShopifyWebhooks.sign(ShopifyWebhooks.sampleOrder, "wrong-secret"))

        assertThat(result)
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("unauthorized")
        assertThat(count("orders")).isZero()
    }

    @Test
    fun `a missing signature is 401`() {
        assertThat(ShopifyWebhooks.send(mvc, signature = null)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the signature is checked before the json is parsed`() {
        // Not JSON at all: with a bad signature this must be 401, not 400.
        val garbage = "<<not json>>".toByteArray()

        assertThat(ShopifyWebhooks.send(mvc, body = garbage, signature = "AAAA")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(ShopifyWebhooks.send(mvc, body = garbage)).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `an unsupported topic is 400`() {
        assertThat(ShopifyWebhooks.send(mvc, topic = "orders/updated"))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .extractingPath("$.violations[0].field")
            .isEqualTo("X-Shopify-Topic")
    }

    @Test
    fun `an invalid order is 400 with every problem listed`() {
        val body = """{"id":1,"name":"#1","currency":"NOK","created_at":"2026-11-05T14:32:11+01:00","line_items":[]}""".toByteArray()

        assertThat(ShopifyWebhooks.send(mvc, body = body))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .extractingPath("$.violations[*].field")
            .asArray()
            .containsExactly("line_items")
    }

    @Test
    fun `a body over the size limit is 413 before anything reads it`() {
        val tooLarge = ByteArray(513 * 1024) { ' '.code.toByte() }

        assertThat(ShopifyWebhooks.send(mvc, body = tooLarge))
            .hasStatus(HttpStatus.CONTENT_TOO_LARGE)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("content-too-large")
    }

    @Test
    fun `a body without content-length is 411`() {
        assertThat(mvc.post().uri(ShopifyWebhooks.PATH).contentType(MediaType.APPLICATION_JSON))
            .hasStatus(HttpStatus.LENGTH_REQUIRED)
    }

    private fun count(table: String): Int = jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()
}
