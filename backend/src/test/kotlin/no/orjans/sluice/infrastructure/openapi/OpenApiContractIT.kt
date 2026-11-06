package no.orjans.sluice.infrastructure.openapi

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Guards the API contract the Angular client is generated from (ADR 0006).
 *
 * Fails when the live spec differs from `frontend/openapi/sluice-api.json`. After an intended
 * API change, update the committed spec with:
 * `./gradlew test --tests '*OpenApiContractIT' -PupdateOpenApi`
 */
@IntegrationTest
class OpenApiContractIT {
    @Autowired
    lateinit var mvc: MockMvcTester

    private val json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    @Test
    fun `committed openapi spec matches the running api`() {
        val response = mvc.get().uri("/v3/api-docs").exchange()
        assertThat(response).hasStatusOk()
        val live = json.readTree(response.response.contentAsString)

        val contract = File(System.getProperty(CONTRACT_PROPERTY) ?: DEFAULT_CONTRACT_PATH)
        if (System.getProperty(UPDATE_PROPERTY).toBoolean()) {
            contract.parentFile.mkdirs()
            contract.writeText(json.writeValueAsString(live) + "\n")
            return
        }
        if (!contract.exists()) fail<Unit>("Missing ${contract.path}. Run: ./gradlew test --tests '*OpenApiContractIT' -PupdateOpenApi")

        assertThat(json.readTree(contract))
            .describedAs("OpenAPI spec is out of date. Run: ./gradlew test --tests '*OpenApiContractIT' -PupdateOpenApi")
            .isEqualTo(live)
    }

    private companion object {
        const val CONTRACT_PROPERTY = "sluice.openapi.contract"
        const val UPDATE_PROPERTY = "sluice.openapi.update"
        const val DEFAULT_CONTRACT_PATH = "../frontend/openapi/sluice-api.json"
    }
}
