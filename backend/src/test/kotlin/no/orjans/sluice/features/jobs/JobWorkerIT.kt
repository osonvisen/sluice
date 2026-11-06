package no.orjans.sluice.features.jobs

import no.orjans.sluice.features.jobs.worker.JobWorker
import no.orjans.sluice.support.DatabaseCleaner
import no.orjans.sluice.support.FixedRandom
import no.orjans.sluice.support.IntegrationTest
import no.orjans.sluice.support.MutableClock
import no.orjans.sluice.support.ScriptedJobHandler
import no.orjans.sluice.support.ScriptedPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@IntegrationTest
class JobWorkerIT {
    @Autowired
    lateinit var worker: JobWorker

    @Autowired
    lateinit var queue: JobQueue

    @Autowired
    lateinit var handler: ScriptedJobHandler

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var transactions: TransactionTemplate

    @Autowired
    lateinit var properties: JobsProperties

    @Autowired
    lateinit var random: FixedRandom

    @Autowired
    lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun reset() {
        cleaner.clean()
        handler.reset()
        clock.set(MutableClock.START)
        random.value = FixedRandom.MIDDLE
    }

    @Test
    fun `a successful job ends SUCCEEDED with one attempt in its history`() {
        val id = enqueue("ok")

        assertThat(worker.runOnce()).isEqualTo(1)

        assertThat(job(id)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1)
        assertThat(attempts(id)).containsExactly("SUCCEEDED")
        assertThat(handlerWrites("ok")).isEqualTo(1)
    }

    @Test
    fun `a temporary error is retried after the backoff and then succeeds`() {
        handler.script("flaky", { JobOutcome.TemporaryFailure("ERP timed out") })
        val id = enqueue("flaky")

        worker.runOnce()
        assertThat(job(id)).containsEntry("status", "FAILED").containsEntry("last_error", "ERP timed out")
        assertThat(nextRunAt(id)).isEqualTo(MutableClock.START.plus(properties.initialBackoff))

        clock.advance(properties.initialBackoff.minusSeconds(1))
        assertThat(worker.runOnce()).describedAs("not due before the backoff has passed").isZero()

        clock.advance(Duration.ofSeconds(1))
        worker.runOnce()
        assertThat(job(id)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2)
        assertThat(attempts(id)).containsExactly("TEMPORARY_FAILURE", "SUCCEEDED")
    }

    @Test
    fun `the handler runs outside any transaction`() {
        enqueue("no-tx")

        worker.runOnce()

        assertThat(handler.ranInTransaction).isFalse()
    }

    @Test
    fun `a failed attempt's effects are recorded too, so the history is complete`() {
        handler.script("record-failure", { JobOutcome.TemporaryFailure("boom") })
        enqueue("record-failure")

        worker.runOnce()

        assertThat(handlerWrites("record-failure")).isEqualTo(1)
    }

    @Test
    fun `a slow worker whose lease expired loses its result, the new owner's result counts`() {
        val inHandler = CountDownLatch(1)
        val release = CountDownLatch(1)
        handler.script(
            "slow",
            {
                inHandler.countDown()
                release.await(10, TimeUnit.SECONDS)
                JobOutcome.Succeeded()
            },
        )
        val id = enqueue("slow")
        val slowWorker = Executors.newSingleThreadExecutor().submit<Int> { worker.runOnce() }
        assertThat(inHandler.await(10, TimeUnit.SECONDS)).isTrue()

        // While the first worker is stuck in the handler: its lease expires and another poll takes over.
        clock.advance(properties.lease.plusSeconds(1))
        worker.runOnce()
        assertThat(job(id)).containsEntry("status", "FAILED")
        clock.advance(properties.maxBackoff)
        worker.runOnce()
        assertThat(job(id)).containsEntry("status", "SUCCEEDED")

        release.countDown()
        slowWorker.get(10, TimeUnit.SECONDS)

        assertThat(
            job(id),
        ).describedAs("the stale result changed nothing").containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2)
        assertThat(handlerWrites("slow")).describedAs("only the owner's effects were applied").isEqualTo(1)
        assertThat(attempts(id)).containsExactly("LEASE_EXPIRED", "SUCCEEDED")
    }

    @Test
    fun `if the effects fail, the job stays RUNNING and is retried after the lease, like a crash`() {
        jdbc.sql("ALTER TABLE scripted_job_writes ADD CONSTRAINT no_poison CHECK (job_key <> 'poison')").update()
        try {
            val id = enqueue("poison")

            worker.runOnce()
            assertThat(job(id)).containsEntry("status", "RUNNING")

            clock.advance(properties.lease.plusSeconds(1))
            worker.runOnce()
            assertThat(job(id)).containsEntry("status", "FAILED")
            assertThat(attempts(id)).containsExactly("LEASE_EXPIRED")
        } finally {
            jdbc.sql("ALTER TABLE scripted_job_writes DROP CONSTRAINT no_poison").update()
        }
    }

    @Test
    fun `a retry-after from the handler decides next run, capped at max backoff`() {
        handler.script("told-90s", { JobOutcome.TemporaryFailure("rate limited", retryAfter = Duration.ofSeconds(90)) })
        handler.script("told-2h", { JobOutcome.TemporaryFailure("rate limited", retryAfter = properties.maxBackoff.multipliedBy(2)) })
        val ninetySeconds = enqueue("told-90s")
        val twoHours = enqueue("told-2h")

        worker.runOnce()

        assertThat(nextRunAt(ninetySeconds)).isEqualTo(MutableClock.START.plusSeconds(90))
        assertThat(nextRunAt(twoHours)).isEqualTo(MutableClock.START.plus(properties.maxBackoff))
    }

    @Test
    fun `backoff gets jitter from the injected random generator`() {
        handler.script("jitter", { JobOutcome.TemporaryFailure("down") })
        random.value = 0.0
        val id = enqueue("jitter")

        worker.runOnce()

        val lowest = properties.initialBackoff.multipliedBy(((1 - properties.backoffJitter) * 1000).toLong()).dividedBy(1000)
        assertThat(nextRunAt(id)).isEqualTo(MutableClock.START.plus(lowest))
    }

    @Test
    fun `after max attempts with temporary errors the job is DEAD and EXHAUSTED`() {
        handler.script("always-down", *Array(properties.maxAttempts) { { JobOutcome.TemporaryFailure("ERP unavailable") } })
        val id = enqueue("always-down")

        repeat(properties.maxAttempts) {
            worker.runOnce()
            clock.advance(properties.maxBackoff)
        }

        assertThat(job(id))
            .containsEntry("status", "DEAD")
            .containsEntry("dead_reason", "EXHAUSTED")
            .containsEntry("attempts", properties.maxAttempts)
        assertThat(handler.calls("always-down")).isEqualTo(properties.maxAttempts)
        assertThat(worker.runOnce()).describedAs("a dead job is never picked up again").isZero()
    }

    @Test
    fun `a permanent error goes straight to DEAD without retries`() {
        handler.script("bad-data", { JobOutcome.PermanentFailure("Missing SKU on line 2") })
        val id = enqueue("bad-data")

        worker.runOnce()
        clock.advance(properties.maxBackoff)
        worker.runOnce()

        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("dead_reason", "PERMANENT").containsEntry("attempts", 1)
        assertThat(handler.calls("bad-data")).isEqualTo(1)
    }

    @Test
    fun `an unexpected exception counts as a temporary error, without its message`() {
        handler.script("bug", { throw IllegalStateException("customer kari@example.com broke it") })
        val id = enqueue("bug")

        worker.runOnce()

        assertThat(job(id)).containsEntry("status", "FAILED").containsEntry("last_error", "Unexpected error: IllegalStateException")
    }

    @Test
    fun `a job type without a handler is DEAD and PERMANENT`() {
        val id = enqueue("orphan", type = JobType("test.no-such-handler"))

        worker.runOnce()

        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("dead_reason", "PERMANENT")
        assertThat(job(id)["last_error"].toString()).contains("No handler")
    }

    @Test
    fun `an unreadable payload is DEAD and PERMANENT`() {
        val id = enqueue("broken")
        jdbc.sql("UPDATE jobs SET payload = '{\"unexpected\": true}'::jsonb WHERE id = :id").param("id", id.value).update()

        worker.runOnce()

        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("dead_reason", "PERMANENT")
    }

    @Test
    fun `a job whose worker crashed is picked up again after the lease expires`() {
        val id = enqueue("crashed")
        // Simulate a worker that claimed the job and died: RUNNING with a lease, and an open attempt.
        transactions.executeWithoutResult { claimWithoutFinishing() }

        clock.advance(properties.lease.minusSeconds(1))
        worker.runOnce()
        assertThat(job(id)).describedAs("lease still valid").containsEntry("status", "RUNNING")

        clock.advance(Duration.ofSeconds(2))
        worker.runOnce()
        assertThat(job(id)).containsEntry("status", "FAILED")
        assertThat(attempts(id)).containsExactly("LEASE_EXPIRED")

        clock.advance(properties.maxBackoff)
        worker.runOnce()
        assertThat(job(id)).containsEntry("status", "SUCCEEDED")
        assertThat(attempts(id)).containsExactly("LEASE_EXPIRED", "SUCCEEDED")
    }

    @Test
    fun `parallel workers never run the same job twice`() {
        val jobs = 40
        (1..jobs).forEach { enqueue("parallel-$it") }
        val workers = 4
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        val runs =
            (1..workers).map {
                pool.submit<Int> {
                    start.await()
                    generateSequence { worker.runOnce() }.takeWhile { it > 0 }.sum()
                }
            }
        start.countDown()
        val claimedPerWorker = runs.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(claimedPerWorker.sum()).isEqualTo(jobs)
        assertThat((1..jobs).map { handler.calls("parallel-$it") }).containsOnly(1)
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED'").query(Int::class.java).single()).isEqualTo(jobs)
    }

    @Test
    fun `the same idempotency key creates one job`() {
        val first = transactions.execute { queue.enqueue(ScriptedJobHandler.TYPE, ScriptedPayload("once"), IdempotencyKey("key-1")) }
        val second = transactions.execute { queue.enqueue(ScriptedJobHandler.TYPE, ScriptedPayload("once"), IdempotencyKey("key-1")) }

        assertThat(first).isInstanceOf(EnqueueResult.Enqueued::class.java)
        assertThat(second).isEqualTo(EnqueueResult.AlreadyEnqueued(first.jobId))
    }

    private fun enqueue(
        key: String,
        type: JobType = ScriptedJobHandler.TYPE,
    ): JobId = transactions.execute { queue.enqueue(type, ScriptedPayload(key), IdempotencyKey(key)) }.jobId

    private fun claimWithoutFinishing() {
        val now = clock.instant()
        jdbc
            .sql(
                """
                WITH claimed AS (
                    UPDATE jobs SET status = 'RUNNING', attempts = attempts + 1, locked_until = :lease, version = version + 1
                    RETURNING id, attempts
                )
                INSERT INTO job_attempts (job_id, attempt, started_at) SELECT id, attempts, :now FROM claimed
                """.trimIndent(),
            ).param("lease", OffsetDateTime.ofInstant(now.plus(properties.lease), clock.zone))
            .param("now", OffsetDateTime.ofInstant(now, clock.zone))
            .update()
    }

    private fun job(id: JobId): Map<String, Any?> =
        jdbc
            .sql("SELECT status, dead_reason, attempts, last_error FROM jobs WHERE id = :id")
            .param("id", id.value)
            .query()
            .singleRow()

    private fun nextRunAt(id: JobId) =
        jdbc
            .sql("SELECT next_run_at FROM jobs WHERE id = :id")
            .param("id", id.value)
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()

    private fun attempts(id: JobId): List<String?> =
        jdbc
            .sql("SELECT outcome FROM job_attempts WHERE job_id = :id ORDER BY id")
            .param("id", id.value)
            .query(String::class.java)
            .list()

    private fun handlerWrites(key: String): Int =
        jdbc
            .sql("SELECT count(*) FROM scripted_job_writes WHERE job_key = :key")
            .param("key", key)
            .query(Int::class.java)
            .single()
}
