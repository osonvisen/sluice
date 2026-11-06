package no.orjans.sluice.features.jobs

import no.orjans.sluice.shared.atUtc
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Puts work on the queue. The only way other areas create jobs.
 *
 * Requires a running transaction (`MANDATORY`): the job must be committed together with the
 * business data that caused it, or not at all (ADR 0002, no dual write).
 */
@Component
@EnableConfigurationProperties(JobsProperties::class)
class JobQueue(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val clock: Clock,
    private val properties: JobsProperties,
) {
    /**
     * Enqueues a job, unless a job with the same [idempotencyKey] already exists. Under parallel
     * calls the unique constraint decides: exactly one caller gets [EnqueueResult.Enqueued].
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(
        type: JobType,
        payload: Any,
        idempotencyKey: IdempotencyKey,
    ): EnqueueResult {
        val now = clock.instant()
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO jobs (id, type, payload, status, max_attempts, next_run_at, idempotency_key, created_at, updated_at)
                    VALUES (:id, :type, :payload::jsonb, 'PENDING', :maxAttempts, :now, :key, :now, :now)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    RETURNING id
                    """.trimIndent(),
                ).param("id", JobId.new().value)
                .param("type", type.value)
                .param("payload", json.writeValueAsString(payload))
                .param("maxAttempts", properties.maxAttempts)
                .param("now", now.atUtc())
                .param("key", idempotencyKey.value)
                .query(UUID::class.java)
                .optional()
        if (inserted.isPresent) return EnqueueResult.Enqueued(JobId.of(inserted.get()))

        val existing =
            jdbc
                .sql("SELECT id FROM jobs WHERE idempotency_key = :key")
                .param("key", idempotencyKey.value)
                .query(UUID::class.java)
                .single()
        return EnqueueResult.AlreadyEnqueued(JobId.of(existing))
    }
}

sealed interface EnqueueResult {
    val jobId: JobId

    data class Enqueued(
        override val jobId: JobId,
    ) : EnqueueResult

    /** A job for the same business event exists already; nothing was created. */
    data class AlreadyEnqueued(
        override val jobId: JobId,
    ) : EnqueueResult
}

/** Identifies the business event behind a job, e.g. `shopify-order:shop.myshopify.com:450789469`. */
@JvmInline
value class IdempotencyKey(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Idempotency key must be 1-$MAX_LENGTH characters" }
    }

    private companion object {
        const val MAX_LENGTH = 200
    }
}
