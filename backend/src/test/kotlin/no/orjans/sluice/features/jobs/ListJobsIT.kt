package no.orjans.sluice.features.jobs

import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.FakeErp
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.MutableClock
import no.orjans.sluice.support.ScriptedJobHandler
import no.orjans.sluice.support.ShopifyWebhooks
import no.orjans.sluice.support.TestJobs
import no.orjans.sluice.support.TestUsers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Duration

@IntegrationTest
class ListJobsIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var jobs: TestJobs

    @Autowired
    lateinit var handler: ScriptedJobHandler

    @Autowired
    lateinit var erp: FakeErp

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        handler.reset()
        erp.reset()
        clock.set(MutableClock.START)
    }

    @Test
    fun `lists jobs newest first with the total`() {
        val first = later { jobs.pending("first") }
        val second = later { jobs.pending("second") }
        val third = later { jobs.pending("third") }

        val body = assertThat(list()).hasStatusOk().bodyJson()

        body.extractingPath("$.items[*].id").asArray().containsExactly("$third", "$second", "$first")
        body.extractingPath("$.total").isEqualTo(3)
        body.extractingPath("$.page").isEqualTo(0)
        body.extractingPath("$.size").isEqualTo(20)
    }

    @Test
    fun `filters on status`() {
        val dead = later { jobs.dead("dead") }
        later { jobs.pending("pending") }

        val body = assertThat(list("status" to "DEAD")).hasStatusOk().bodyJson()

        body.extractingPath("$.items[*].id").asArray().containsExactly("$dead")
        body.extractingPath("$.items[0].deadReason").isEqualTo("PERMANENT")
        body.extractingPath("$.items[0].lastError").isEqualTo("bad data")
        body.extractingPath("$.total").isEqualTo(1)
    }

    @Test
    fun `finds an import job by order number, with or without the hash`() {
        later { receiveOrder(1, "#1001") }
        later { receiveOrder(2, "#1002") }
        later { jobs.pending("not-an-order") }

        listOf("1002", "#1002", " 1002 ").forEach { q ->
            val body = assertThat(list("q" to q)).hasStatusOk().bodyJson()
            body
                .extractingPath("$.items[*].orderNumber")
                .asArray()
                .describedAs("q=%s", q)
                .containsExactly("#1002")
            body.extractingPath("$.items[0].type").isEqualTo("order.import")
        }
    }

    @Test
    fun `finds a job by its id`() {
        val wanted = later { jobs.pending("wanted") }
        later { jobs.pending("other") }

        assertThat(list("q" to "$wanted"))
            .bodyJson()
            .extractingPath("$.items[*].id")
            .asArray()
            .containsExactly("$wanted")
    }

    @Test
    fun `search and status filter combine`() {
        later { receiveOrder(1, "#2001") }

        assertThat(list("q" to "2001", "status" to "DEAD")).bodyJson().extractingPath("$.total").isEqualTo(0)
        assertThat(list("q" to "2001", "status" to "PENDING")).bodyJson().extractingPath("$.total").isEqualTo(1)
    }

    @Test
    fun `pages through the list, and a page past the end is empty with the right total`() {
        val ids = (1..5).map { later { jobs.pending("job-$it") } }.reversed()

        assertThat(list("page" to "0", "size" to "2"))
            .bodyJson()
            .extractingPath("$.items[*].id")
            .asArray()
            .containsExactly("${ids[0]}", "${ids[1]}")
        assertThat(list("page" to "2", "size" to "2"))
            .bodyJson()
            .extractingPath("$.items[*].id")
            .asArray()
            .containsExactly("${ids[4]}")
        val beyond = assertThat(list("page" to "3", "size" to "2")).hasStatusOk().bodyJson()
        beyond.extractingPath("$.items").asArray().isEmpty()
        beyond.extractingPath("$.total").isEqualTo(5)
    }

    @Test
    fun `jobs created in the same instant keep a stable order across pages`() {
        val ids = (1..4).map { jobs.pending("same-time-$it") } // clock not moved: equal created_at

        val firstPage = idsOf(list("page" to "0", "size" to "2"))
        val secondPage = idsOf(list("page" to "1", "size" to "2"))

        assertThat(firstPage + secondPage).containsExactlyInAnyOrderElementsOf(ids.map { "$it" }).doesNotHaveDuplicates()
    }

    @Test
    fun `out of range paging is a validation error, not a silently changed page`() {
        assertThat(
            list("page" to "-1"),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.violations[0].field").isEqualTo("page")
        assertThat(
            list("size" to "0"),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.violations[0].field").isEqualTo("size")
        assertThat(
            list("size" to "101"),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.violations[0].code").isEqualTo("max")
    }

    @Test
    fun `an unknown status or an overlong search is a 400`() {
        assertThat(list("status" to "SLEEPING")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(
            list("q" to "x".repeat(101)),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.violations[0].field").isEqualTo("q")
    }

    @Test
    fun `reading the list needs jobs read`() {
        assertThat(mvc.get().uri("/api/jobs")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(mvc.get().uri("/api/jobs").with(TestUsers.withoutRoles())).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mvc.get().uri("/api/jobs").with(TestUsers.approver())).hasStatusOk()
    }

    private fun list(vararg params: Pair<String, String>) =
        mvc
            .get()
            .uri("/api/jobs")
            .apply { params.forEach { (name, value) -> param(name, value) } }
            .with(TestUsers.operator())

    private fun idsOf(request: MockMvcTester.MockMvcRequestBuilder): List<String> =
        assertThat(request).bodyJson().actual().let { json ->
            Regex("\"id\":\"([0-9a-f-]{36})\"").findAll(json.json).map { it.groupValues[1] }.toList()
        }

    private fun receiveOrder(
        shopifyId: Long,
        orderNumber: String,
    ) = assertThat(ShopifyWebhooks.send(mvc, body = ShopifyWebhooks.sampleOrder(shopifyId, orderNumber))).hasStatusOk()

    /** Each job a second after the previous one, so "newest first" is well defined. */
    private fun <T> later(create: () -> T): T {
        clock.advance(Duration.ofSeconds(1))
        return create()
    }
}
