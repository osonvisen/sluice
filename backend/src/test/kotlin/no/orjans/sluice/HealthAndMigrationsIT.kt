package no.orjans.sluice

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.assertj.MockMvcTester

@IntegrationTest
class HealthAndMigrationsIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `liveness is UP`() {
        assertThat(mvc.get().uri("/health/live"))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.status")
            .isEqualTo("UP")
    }

    @Test
    fun `readiness is UP when the database is reachable`() {
        assertThat(mvc.get().uri("/health/ready"))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.status")
            .isEqualTo("UP")
    }

    @Test
    fun `health does not expose component details`() {
        assertThat(mvc.get().uri("/health/ready"))
            .bodyJson()
            .doesNotHavePath("$.components")
    }

    @Test
    fun `flyway has applied the baseline migration`() {
        val versions =
            jdbc
                .sql("select version from flyway_schema_history where success order by installed_rank")
                .query(String::class.java)
                .list()

        assertThat(versions).contains("1")
    }
}
