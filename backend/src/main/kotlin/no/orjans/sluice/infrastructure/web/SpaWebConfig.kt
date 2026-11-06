package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.shared.ApiPaths
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the Angular app that the build copies into `static/` (ADR 0005).
 *
 * Unknown paths fall back to `index.html` so Angular's router can handle deep links such as
 * `/jobs/42`. Backend paths and missing files (anything with a file extension) stay a real 404.
 */
@Configuration(proxyBeanMethods = false)
class SpaWebConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(SpaFallbackResolver())
    }
}

internal class SpaFallbackResolver : PathResourceResolver() {
    // super applies the path traversal checks; only fall back when it finds nothing.
    override fun getResource(
        resourcePath: String,
        location: Resource,
    ): Resource? =
        super.getResource(resourcePath, location)
            ?: location.createRelative(INDEX_HTML).takeIf { isClientRoute(resourcePath) && it.exists() && it.isReadable }

    private fun isClientRoute(path: String) = !isBackendPath(path) && !looksLikeFile(path)

    private fun isBackendPath(path: String) = BACKEND_PREFIXES.any { path == it || path.startsWith("$it/") }

    private fun looksLikeFile(path: String) = path.substringAfterLast('/').contains('.')

    private companion object {
        const val INDEX_HTML = "index.html"
        val BACKEND_PREFIXES =
            listOf(ApiPaths.API, ApiPaths.WEBHOOKS, ApiPaths.MCP, ApiPaths.HEALTH, "/v3", "/swagger-ui")
                .map { it.removePrefix("/") }
    }
}
