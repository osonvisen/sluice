package no.orjans.sluice.features.jobs

import no.orjans.sluice.shared.Result
import no.orjans.sluice.shared.TypedId
import java.util.UUID

@JvmInline
value class JobId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): JobId = JobId(UUID.randomUUID())

        fun of(value: UUID): JobId = JobId(value)

        fun parse(raw: String): Result<JobId> = TypedId.parse(raw, "jobId", ::JobId)
    }
}
