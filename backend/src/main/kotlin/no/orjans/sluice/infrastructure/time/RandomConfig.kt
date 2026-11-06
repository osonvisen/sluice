package no.orjans.sluice.infrastructure.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.random.RandomGenerator

/** Randomness (backoff jitter) is injected like the clock, so tests can make it deterministic. Not for security. */
@Configuration(proxyBeanMethods = false)
class RandomConfig {
    @Bean
    fun randomGenerator(): RandomGenerator = RandomGenerator.getDefault()
}
