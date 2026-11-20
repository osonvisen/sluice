package no.orjans.sluice.features.jobs

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How an attempt ended, as stored in `job_attempts.outcome` (the database has a matching check constraint).
 * Written by the worker, shown in the job detail.
 */
@Schema(enumAsRef = true)
enum class AttemptOutcome { SUCCEEDED, TEMPORARY_FAILURE, PERMANENT_FAILURE, LEASE_EXPIRED }
