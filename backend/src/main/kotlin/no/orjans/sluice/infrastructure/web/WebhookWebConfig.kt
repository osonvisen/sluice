package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.web.ApiPaths
import no.orjans.sluice.web.WebhookProperties
import no.orjans.sluice.web.WebhookSizeLimitInterceptor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Wires the request size limit onto every webhook endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties::class)
class WebhookWebConfig(
    private val properties: WebhookProperties,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(WebhookSizeLimitInterceptor(properties)).addPathPatterns("${ApiPaths.WEBHOOKS}/**")
    }
}
