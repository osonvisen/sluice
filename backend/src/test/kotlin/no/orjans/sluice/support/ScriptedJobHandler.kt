package no.orjans.sluice.support

import no.orjans.sluice.features.jobs.JobContext
import no.orjans.sluice.features.jobs.JobEffects
import no.orjans.sluice.features.jobs.JobHandler
import no.orjans.sluice.features.jobs.JobOutcome
import no.orjans.sluice.features.jobs.JobType
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class ScriptedPayload(
    val key: String,
)

/**
 * A job handler tests script per payload key: "fail twice, then succeed", "throw", "block until …".
 *
 * Every outcome gets an effect that writes a row to `scripted_job_writes`, so tests can check when
 * effects are applied and when they are discarded. It also records whether it ran inside a
 * transaction (ADR 0010 says it must not).
 */
class ScriptedJobHandler(
    private val jdbc: JdbcClient,
) : JobHandler<ScriptedPayload> {
    override val type = TYPE
    override val payloadType = ScriptedPayload::class.java

    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<() -> JobOutcome>>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    var ranInTransaction: Boolean? = null
        private set

    /** Outcomes in order; when the script runs out, every further call succeeds. */
    fun script(
        key: String,
        vararg steps: () -> JobOutcome,
    ) {
        scripts[key] = ConcurrentLinkedQueue(steps.toList())
    }

    fun calls(key: String): Int = calls[key]?.get() ?: 0

    fun reset() {
        scripts.clear()
        calls.clear()
        ranInTransaction = null
    }

    override fun handle(
        payload: ScriptedPayload,
        context: JobContext,
    ): JobOutcome {
        ranInTransaction = TransactionSynchronizationManager.isActualTransactionActive()
        calls.computeIfAbsent(payload.key) { AtomicInteger() }.incrementAndGet()
        val step = scripts[payload.key]?.poll() ?: { JobOutcome.Succeeded() }
        val write = JobEffects { jdbc.sql("INSERT INTO scripted_job_writes (job_key) VALUES (:key)").param("key", payload.key).update() }
        return when (val outcome = step()) {
            is JobOutcome.Succeeded -> outcome.copy(effects = write)
            is JobOutcome.TemporaryFailure -> outcome.copy(effects = write)
            is JobOutcome.PermanentFailure -> outcome.copy(effects = write)
        }
    }

    companion object {
        val TYPE = JobType("test.scripted")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestJobHandlersConfiguration {
    @Bean
    fun scriptedJobHandler(jdbc: JdbcClient): ScriptedJobHandler {
        // Test-only table, outside Flyway: it records the handler's effects.
        jdbc.sql("CREATE TABLE IF NOT EXISTS scripted_job_writes (job_key text NOT NULL)").update()
        return ScriptedJobHandler(jdbc)
    }
}
