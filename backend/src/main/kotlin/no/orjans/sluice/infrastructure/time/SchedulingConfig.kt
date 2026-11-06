package no.orjans.sluice.infrastructure.time

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Turns on `@Scheduled` (the job worker's polling). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig
