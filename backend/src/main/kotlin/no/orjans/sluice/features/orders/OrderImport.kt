package no.orjans.sluice.features.orders

import no.orjans.sluice.features.jobs.JobType
import java.util.UUID

/** The contract between the slice that receives orders and the job that imports them. */
object OrderImport {
    val JOB_TYPE = JobType("order.import")
}

/** Only the ID: the job reads the order from the database, so the payload never holds order data. */
data class ImportOrderPayload(
    val orderId: UUID,
)
