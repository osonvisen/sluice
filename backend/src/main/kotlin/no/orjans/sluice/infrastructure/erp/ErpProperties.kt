package no.orjans.sluice.infrastructure.erp

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("sluice.erp")
data class ErpProperties(
    val baseUrl: URI,
    val connectTimeout: Duration,
    /** Longest wait for the ERP's answer. Together with [connectTimeout] well below the job lease (ADR 0010). */
    val readTimeout: Duration,
)
