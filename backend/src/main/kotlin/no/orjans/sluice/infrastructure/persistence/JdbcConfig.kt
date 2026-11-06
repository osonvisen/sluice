package no.orjans.sluice.infrastructure.persistence

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration

/**
 * Spring Data JDBC setup. Converters for typed IDs and value objects are registered here, once,
 * so the slices can use them in their aggregates without any mapping code (ADR 0003).
 */
@Configuration(proxyBeanMethods = false)
class JdbcConfig : AbstractJdbcConfiguration() {
    override fun userConverters(): List<Any> = listOf(TypedIdWritingConverter)
}
