package no.orjans.sluice.support

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** Empties the application tables between tests. The schema and Flyway's history stay. */
@Component
class DatabaseCleaner(
    private val jdbc: JdbcClient,
) {
    fun clean() {
        jdbc.sql("TRUNCATE order_lines, orders, job_attempts, jobs, scripted_job_writes CASCADE").update()
    }
}
