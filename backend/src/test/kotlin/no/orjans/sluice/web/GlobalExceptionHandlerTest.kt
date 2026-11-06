package no.orjans.sluice.web

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import no.orjans.sluice.infrastructure.web.LocaleConfig
import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.ErrorCode
import no.orjans.sluice.shared.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(GlobalExceptionHandlerTest.TestEndpoints::class)
@Import(ProblemDetailFactory::class, LocaleConfig::class, CorrelationIdFilter::class, GlobalExceptionHandlerTest.TestEndpoints::class)
class GlobalExceptionHandlerTest {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `bean validation errors become validation-failed with one violation per field`() {
        val result =
            mvc
                .post()
                .uri("/test/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sku": "", "quantity": 0}""")

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
        val body = assertThat(result).bodyJson()
        body.extractingPath("$.code").isEqualTo("validation-failed")
        body.extractingPath("$.violations[?(@.field == 'sku')].code").asArray().containsExactly("not-blank")
        body.extractingPath("$.violations[?(@.field == 'quantity')].code").asArray().containsExactly("positive")
    }

    @Test
    fun `violation messages are translated`() {
        val result =
            mvc
                .post()
                .uri("/test/orders")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "nb")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sku": "", "quantity": 1}""")

        assertThat(result)
            .bodyJson()
            .extractingPath("$.violations[0].message")
            .isEqualTo("Kan ikke være tomt.")
    }

    @Test
    fun `malformed json is a bad-request problem`() {
        assertThat(
            mvc
                .post()
                .uri("/test/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"),
        ).hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("bad-request")
    }

    // Without Jackson's Kotlin module the missing field would become null in a non-null property
    // and fail later as a 500. With it, the request is rejected at the boundary as a 400.
    @Test
    fun `missing required field is a bad-request problem, not a null in a non-null property`() {
        assertThat(
            mvc
                .post()
                .uri("/test/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity": 1}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("bad-request")
    }

    @Test
    fun `a failed result from an endpoint becomes the matching problem`() {
        val result = mvc.get().uri("/test/jobs/42")

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("not-found")
    }

    @Test
    fun `a business rule failure is 422 with the rule's own code`() {
        assertThat(mvc.get().uri("/test/rule"))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("job-not-retryable")
    }

    @Test
    fun `unexpected exceptions become a 500 without internal details`() {
        val result = mvc.get().uri("/test/boom")

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        val body = assertThat(result).bodyJson()
        body.extractingPath("$.code").isEqualTo("internal-error")
        body.extractingPath("$.correlationId").isNotNull()
        assertThat(result).bodyText().doesNotContain(SECRET_INTERNAL_MESSAGE)
    }

    @RestController
    class TestEndpoints {
        @PostMapping("/test/orders")
        fun create(
            @Valid @RequestBody request: TestOrderRequest,
        ): TestOrderRequest = request

        @GetMapping("/test/jobs/{id}")
        fun job(
            @PathVariable id: String,
        ): String = Result.failure(AppError.NotFound("job", id)).getOrThrow()

        @GetMapping("/test/rule")
        fun rule(): String = Result.failure(AppError.RuleViolation(ErrorCode("job-not-retryable"))).getOrThrow()

        @GetMapping("/test/boom")
        fun boom(): Nothing = throw IllegalStateException(SECRET_INTERNAL_MESSAGE)
    }

    data class TestOrderRequest(
        @field:NotBlank val sku: String,
        @field:Positive val quantity: Int,
    )

    private companion object {
        const val SECRET_INTERNAL_MESSAGE = "connection string jdbc:postgresql://internal"
    }
}
