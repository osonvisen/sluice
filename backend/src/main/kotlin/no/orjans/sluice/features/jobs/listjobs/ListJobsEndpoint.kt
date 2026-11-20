package no.orjans.sluice.features.jobs.listjobs

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import no.orjans.sluice.features.jobs.JobStatus
import no.orjans.sluice.shared.Page
import no.orjans.sluice.shared.PageRequest
import no.orjans.sluice.shared.Permissions
import no.orjans.sluice.shared.map
import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.getOrThrow
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The job overview: newest first, optionally filtered by status and searched by order number or job ID. */
@RestController
@Tag(name = "Jobs")
class ListJobsEndpoint(
    private val handler: ListJobsHandler,
) {
    @GetMapping("${ApiPaths.API}/jobs", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasAuthority('${Permissions.JOBS_READ}')")
    fun listJobs(
        @RequestParam(required = false) status: JobStatus?,
        @Parameter(description = "Order number (with or without #) or job ID, exact match")
        @RequestParam(required = false) q: String?,
        @Parameter(description = "0-based page number")
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = PageRequest.DEFAULT_SIZE.toString()) size: Int,
    ): Page<JobListItem> = JobListQuery.of(status, q, page, size).map(handler::handle).getOrThrow()
}
