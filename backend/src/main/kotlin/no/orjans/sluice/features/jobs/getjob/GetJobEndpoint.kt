package no.orjans.sluice.features.jobs.getjob

import io.swagger.v3.oas.annotations.tags.Tag
import no.orjans.sluice.features.jobs.JobId
import no.orjans.sluice.shared.flatMap
import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.ETags
import no.orjans.sluice.web.getOrThrow
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** One job with its version as ETag, which `POST …/retry` needs in `If-Match`. */
@RestController
@Tag(name = "Jobs")
class GetJobEndpoint(
    private val handler: GetJobHandler,
) {
    @GetMapping("${ApiPaths.API}/jobs/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getJob(
        @PathVariable id: String,
    ): ResponseEntity<GetJobResponse> {
        val job = JobId.parse(id).flatMap(handler::handle).getOrThrow()
        return ResponseEntity.ok().eTag(ETags.of(job.version)).body(job.response)
    }
}
