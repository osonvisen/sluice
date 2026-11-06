package no.orjans.sluice.features.jobs.worker

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Polls the queue on a fixed delay (the next poll starts when the previous one is done, so polls
 * never overlap within one instance). Off in tests and in the `migrate` profile.
 */
@Component
@ConditionalOnProperty("sluice.jobs.worker.enabled", havingValue = "true")
class JobPollingScheduler(
    private val worker: JobWorker,
) {
    @Scheduled(fixedDelayString = "\${sluice.jobs.worker.poll-interval}")
    fun poll() {
        worker.runOnce()
    }
}
