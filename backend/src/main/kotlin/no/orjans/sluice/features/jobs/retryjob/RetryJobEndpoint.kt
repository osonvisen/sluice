package no.orjans.sluice.features.jobs.retryjob

import io.swagger.v3.oas.annotations.tags.Tag
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.ETags
import no.orjans.sluice.web.getOrThrow
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Runs a `FAILED` or `DEAD` job again (ADR 0009). Requires `If-Match` with the version from `GET /api/jobs/{id}`:
 * 428 without it, 412 if someone else changed the job, 409 `job-not-retryable` if it is not failed or dead.
 */
@RestController
@Tag(name = "Jobs")
class RetryJobEndpoint(
    private val handler: RetryJobHandler,
) {
    @PostMapping("${ApiPaths.API}/jobs/{id}/retry", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun retryJob(
        @PathVariable id: String,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<RetryJobResponse> {
        val expectedVersion = ETags.requiredVersion(ifMatch)
        val jobId = JobId.parse(id).getOrThrow()
        val retried = handler.handle(jobId, expectedVersion).getOrThrow()
        return ResponseEntity.ok().eTag(ETags.of(retried.version)).body(retried.response)
    }
}
