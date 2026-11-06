package no.orjans.sluice.features.system.appinfo

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.assertj.MockMvcTester

@IntegrationTest
class GetAppInfoEndpointIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `returns application name and build version`() {
        val response = assertThat(mvc.get().uri("/api/app-info")).hasStatusOk().bodyJson()

        response.extractingPath("$.name").isEqualTo("sluice")
        response.extractingPath("$.version").isEqualTo("0.0.1-SNAPSHOT")
    }
}
