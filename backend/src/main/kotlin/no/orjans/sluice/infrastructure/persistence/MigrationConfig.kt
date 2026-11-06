package no.orjans.sluice.infrastructure.persistence

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Migrations run as a separate step before the app starts, not as a side effect of starting it.
 *
 * - The `migrate` profile migrates the database and exits (`--spring.profiles.active=migrate`).
 * - A normal start only validates: it refuses to start if migrations are pending or an applied
 *   migration was changed. Migrations from a newer app version are ignored (Flyway's default
 *   `*:future`), so the previous version can still start during a rollback (expand → contract).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties::class)
class MigrationConfig {
    @Bean
    fun flywayMigrationStrategy(properties: MigrationProperties): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            if (properties.migrateOnStartup) flyway.migrate() else flyway.validate()
        }
}

@ConfigurationProperties("sluice.database")
data class MigrationProperties(
    /** True only in the `migrate` profile and in integration tests. */
    val migrateOnStartup: Boolean = false,
)
