package no.orjans.sluice.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * Base for integration tests: the full application against a real Postgres in Testcontainers,
 * called through MockMvc. Keep the configuration identical across tests so the context is reused.
 *
 * Tests migrate their own throwaway database on startup; the app itself only validates (MigrationConfig).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(properties = ["sluice.database.migrate-on-startup=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
