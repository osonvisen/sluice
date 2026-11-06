package no.orjans.sluice.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * Base for integration tests: the full application against a real Postgres in Testcontainers,
 * called through MockMvc. Keep the configuration identical across tests so the context is reused.
 *
 * - Tests migrate their own throwaway database on startup; the app itself only validates (MigrationConfig).
 * - The job worker does not poll: tests call `JobWorker.runOnce()` and move the [MutableClock] themselves.
 * - Backoff jitter is zero by default ([FixedRandom]); the ERP is a [FakeErp] on WireMock.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    properties = [
        "sluice.database.migrate-on-startup=true",
        "sluice.jobs.worker.enabled=false",
        "sluice.shopify.webhook-secret=${IntegrationTest.SHOPIFY_SECRET}",
        // Short, so timeout tests are quick; still far below the job lease.
        "sluice.erp.read-timeout=2s",
    ],
)
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    TestClockConfiguration::class,
    TestJobHandlersConfiguration::class,
    FakeErpConfiguration::class,
    DatabaseCleaner::class,
)
annotation class IntegrationTest {
    companion object {
        const val SHOPIFY_SECRET = "test-shopify-secret"
    }
}
