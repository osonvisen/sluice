package no.orjans.sluice.infrastructure.security

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Guards against an endpoint that forgets to say who may call it. The filter chain only requires *a*
 * login under `/api`; the permission is checked per endpoint, so a missing `@PreAuthorize` would let
 * any logged-in user in. Checked on the running app's real mappings, so a new endpoint is covered
 * without anyone remembering to add it here.
 */
@IntegrationTest
class EndpointAuthorizationIT {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mappings: RequestMappingHandlerMapping

    private val endpoints get() = mappings.handlerMethods.map { (info, method) -> info.patternValues to method }

    @Test
    fun `every api endpoint declares who may call it with PreAuthorize`() {
        val api = endpoints.filter { (paths, _) -> paths.any { it.startsWith("/api/") } }
        assertThat(api).isNotEmpty

        val unguarded =
            api
                .filterNot { (_, method) -> method.hasPreAuthorize() }
                .map { (paths, method) -> "$paths ${method.shortLogMessage}" }
        assertThat(unguarded).describedAs("endpoints under /api without @PreAuthorize").isEmpty()
    }

    /** The filter chain lets every other path through for the Angular app, so a controller elsewhere would be open. */
    @Test
    fun `our endpoints live under api or webhooks only`() {
        val ours = endpoints.filter { (_, method) -> method.beanType.packageName.startsWith(BASE_PACKAGE) }
        assertThat(ours).isNotEmpty

        val elsewhere = ours.filterNot { (paths, _) -> paths.all { it.startsWith("/api/") || it.startsWith("/webhooks/") } }
        assertThat(elsewhere.map { it.first }).describedAs("endpoints outside /api and /webhooks").isEmpty()
    }

    private fun HandlerMethod.hasPreAuthorize() =
        AnnotatedElementUtils.hasAnnotation(method, PreAuthorize::class.java) ||
            AnnotatedElementUtils.hasAnnotation(beanType, PreAuthorize::class.java)

    private companion object {
        const val BASE_PACKAGE = "no.orjans.sluice"
    }
}
