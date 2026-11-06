package no.orjans.sluice.infrastructure.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The only place that reads the system clock. Code that needs "now" injects [Clock], so tests can fix time. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
