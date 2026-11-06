package no.orjans.sluice.infrastructure.web

import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import tools.jackson.module.kotlin.KotlinModule

/**
 * HTTP (de)serialisation uses Jackson 3 with the Kotlin module. Jackson 2 is on the classpath only
 * because springdoc's swagger-core still needs it for building the OpenAPI spec.
 */
@IntegrationTest
class JacksonSetupIT {
    @Autowired
    lateinit var handlerAdapter: RequestMappingHandlerAdapter

    // The Jackson 2 converter is deprecated in Spring 7; referring to it is the point of this test.
    @Suppress("DEPRECATION")
    @Test
    fun `json converter for http is jackson 3, never jackson 2`() {
        val converters = handlerAdapter.messageConverters

        assertThat(converters).anyMatch { it is JacksonJsonHttpMessageConverter }
        // Fully qualified: an import of a deprecated class cannot be suppressed locally.
        assertThat(converters).noneMatch { it is org.springframework.http.converter.json.MappingJackson2HttpMessageConverter }
    }

    @Test
    fun `every jackson 3 http converter has the kotlin module`() {
        val converters = handlerAdapter.messageConverters.filterIsInstance<JacksonJsonHttpMessageConverter>()

        assertThat(converters).isNotEmpty.allSatisfy { converter ->
            assertThat(converter.mapper.registeredModules()).anyMatch { it is KotlinModule }
        }
    }
}
