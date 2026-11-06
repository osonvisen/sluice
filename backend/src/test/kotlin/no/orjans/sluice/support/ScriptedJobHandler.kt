package no.orjans.sluice.support

import no.orjans.sluice.features.jobs.JobHandler
import no.orjans.sluice.features.jobs.JobOutcome
import no.orjans.sluice.features.jobs.JobType
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class ScriptedPayload(
    val key: String,
)

/**
 * A job handler tests script per payload key: "fail twice, then succeed", "throw", and so on.
 * Counts calls, and writes a row to `scripted_job_writes` on every call, so tests can check
 * that a failed attempt's writes are rolled back.
 */
class ScriptedJobHandler(
    private val jdbc: JdbcClient,
) : JobHandler<ScriptedPayload> {
    override val type = TYPE
    override val payloadType = ScriptedPayload::class.java

    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<() -> JobOutcome>>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

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
    }

    override fun handle(payload: ScriptedPayload): JobOutcome {
        calls.computeIfAbsent(payload.key) { AtomicInteger() }.incrementAndGet()
        jdbc.sql("INSERT INTO scripted_job_writes (job_key) VALUES (:key)").param("key", payload.key).update()
        val step = scripts[payload.key]?.poll() ?: { JobOutcome.Succeeded }
        return step()
    }

    companion object {
        val TYPE = JobType("test.scripted")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestJobHandlersConfiguration {
    @Bean
    fun scriptedJobHandler(jdbc: JdbcClient): ScriptedJobHandler {
        // Test-only table, outside Flyway: it records the handler's writes so rollback can be checked.
        jdbc.sql("CREATE TABLE IF NOT EXISTS scripted_job_writes (job_key text NOT NULL)").update()
        return ScriptedJobHandler(jdbc)
    }
}
