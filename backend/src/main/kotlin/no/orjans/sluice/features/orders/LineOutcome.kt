package no.orjans.sluice.features.orders

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How one order line fared in one attempt, as stored in `job_line_log.outcome` (the database has a matching
 * check constraint). Written by the import job, shown in the job detail.
 */
@Schema(enumAsRef = true)
enum class LineOutcome { ACCEPTED, REJECTED, ORDER_REJECTED, INVALID, NO_RESPONSE }
