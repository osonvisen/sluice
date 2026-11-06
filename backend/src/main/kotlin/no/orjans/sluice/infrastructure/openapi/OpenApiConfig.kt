package no.orjans.sluice.infrastructure.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Metadata for the generated OpenAPI spec. The spec is the contract the Angular client is
 * generated from (ADR 0006); `OpenApiContractIT` fails when it drifts from the committed copy.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Sluice API")
                    .description("Integration platform between webshop, warehouse and ERP.")
                    .version(CONTRACT_VERSION),
            )
            // A fixed server URL keeps the spec identical no matter which host it was fetched from.
            .servers(listOf(Server().url("/").description("Same origin as the web app (ADR 0005)")))

    private companion object {
        // Contract version, not the build version, so the committed spec stays stable between builds.
        const val CONTRACT_VERSION = "v1"
    }
}
