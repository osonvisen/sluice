package no.orjans.sluice.features.jobs

import no.orjans.sluice.features.jobs.worker.JobWorker
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.MutableClock
import no.orjans.sluice.support.ScriptedJobHandler
import no.orjans.sluice.support.ScriptedPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class JobsApiIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var worker: JobWorker

    @Autowired
    lateinit var queue: JobQueue

    @Autowired
    lateinit var handler: ScriptedJobHandler

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var transactions: TransactionTemplate

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        handler.reset()
        clock.set(MutableClock.START)
    }

    @Test
    fun `get returns the job with its version as etag and times in utc`() {
        val id = enqueue("get")

        val result = mvc.get().uri("/api/jobs/$id")

        assertThat(result).hasStatusOk().hasHeader(HttpHeaders.ETAG, "\"0\"")
        val body = assertThat(result).bodyJson()
        body.extractingPath("$.status").isEqualTo("PENDING")
        body.extractingPath("$.nextRunAt").isEqualTo("2026-11-06T09:00:00Z")
    }

    @Test
    fun `get of an unknown job is 404, of a malformed id 400`() {
        assertThat(mvc.get().uri("/api/jobs/${UUID.randomUUID()}")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.get().uri("/api/jobs/not-a-uuid"))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .extractingPath("$.violations[0].code")
            .isEqualTo("invalid-id")
    }

    @Test
    fun `retry runs a dead job again from attempt zero and returns the new etag`() {
        val id = deadJob("dead")
        val etag = etagOf(id)

        val result =
            mvc
                .post()
                .uri("/api/jobs/$id/retry")
                .header(HttpHeaders.IF_MATCH, etag)
                .exchange()

        assertThat(result).hasStatusOk()
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING")
        assertThat(result).bodyJson().extractingPath("$.attempts").isEqualTo(0)
        assertThat(result.response.getHeader(HttpHeaders.ETAG)).isNotEqualTo(etag)
        assertThat(mvc.get().uri("/api/jobs/$id")).bodyJson().extractingPath("$.deadReason").isNull()

        worker.runOnce()
        assertThat(mvc.get().uri("/api/jobs/$id")).bodyJson().extractingPath("$.status").isEqualTo("SUCCEEDED")
    }

    @Test
    fun `retry without if-match is 428`() {
        val id = deadJob("no-if-match")

        assertThat(mvc.post().uri("/api/jobs/$id/retry"))
            .hasStatus(HttpStatus.PRECONDITION_REQUIRED)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("precondition-required")
    }

    @Test
    fun `retry with a stale version is 412 and changes nothing`() {
        val id = deadJob("stale")
        val staleEtag = "\"0\""

        assertThat(mvc.post().uri("/api/jobs/$id/retry").header(HttpHeaders.IF_MATCH, staleEtag))
            .hasStatus(HttpStatus.PRECONDITION_FAILED)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("precondition-failed")
        assertThat(mvc.get().uri("/api/jobs/$id")).bodyJson().extractingPath("$.status").isEqualTo("DEAD")
    }

    @Test
    fun `retry of a job that is not failed or dead is 409 job-not-retryable`() {
        val id = enqueue("succeeded")
        worker.runOnce()

        assertThat(mvc.post().uri("/api/jobs/$id/retry").header(HttpHeaders.IF_MATCH, etagOf(id)))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("job-not-retryable")
    }

    @Test
    fun `retry of an unknown job is 404`() {
        assertThat(mvc.post().uri("/api/jobs/${UUID.randomUUID()}/retry").header(HttpHeaders.IF_MATCH, "\"0\""))
            .hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `two concurrent retries with the same version, exactly one wins`() {
        val id = deadJob("race")
        val etag = etagOf(id)
        val clients = 6
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(clients)
        val statuses =
            (1..clients).map {
                pool.submit<Int> {
                    start.await()
                    mvc
                        .post()
                        .uri("/api/jobs/$id/retry")
                        .header(HttpHeaders.IF_MATCH, etag)
                        .exchange()
                        .response.status
                }
            }
        start.countDown()
        val results = statuses.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(results.count { it == HttpStatus.OK.value() }).isEqualTo(1)
        assertThat(results.filter { it != HttpStatus.OK.value() }).containsOnly(HttpStatus.PRECONDITION_FAILED.value())
    }

    private fun enqueue(key: String): JobId =
        transactions.execute { queue.enqueue(ScriptedJobHandler.TYPE, ScriptedPayload(key), IdempotencyKey(key)) }.jobId

    private fun deadJob(key: String): JobId {
        handler.script(key, { JobOutcome.PermanentFailure("bad data") })
        val id = enqueue(key)
        worker.runOnce()
        return id
    }

    private fun etagOf(id: JobId): String =
        checkNotNull(
            mvc
                .get()
                .uri("/api/jobs/$id")
                .exchange()
                .response
                .getHeader(HttpHeaders.ETAG),
        )
}
