package no.orjans.sluice.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Postgres container for the whole test run. Spring caches the application context per
 * configuration, so every test using [IntegrationTest] shares both the context and the container.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))

    companion object {
        // Same major version as docker-compose.yml, so tests run against what we run locally.
        const val POSTGRES_IMAGE = "postgres:18.6-alpine"
    }
}
