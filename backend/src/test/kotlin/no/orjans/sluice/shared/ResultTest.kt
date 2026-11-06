package no.orjans.sluice.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultTest {
    private val notFound = AppError.NotFound("job", "1")

    @Test
    fun `map transforms a success`() {
        assertThat(Result.success(2).map { it * 2 }).isEqualTo(Result.success(4))
    }

    @Test
    fun `map leaves a failure untouched`() {
        val failure: Result<Int> = Result.failure(notFound)

        assertThat(failure.map { it * 2 }).isEqualTo(Result.failure(notFound))
    }

    @Test
    fun `flatMap chains operations and stops at the first failure`() {
        var secondStepCalled = false
        val result =
            Result
                .success(1)
                .flatMap { Result.failure(notFound) }
                .flatMap {
                    secondStepCalled = true
                    Result.success(it)
                }

        assertThat(result).isEqualTo(Result.failure(notFound))
        assertThat(secondStepCalled).isFalse()
    }

    @Test
    fun `fold picks the branch`() {
        assertThat(Result.success("ok").fold({ it }, { "failed" })).isEqualTo("ok")
        assertThat(Result.failure(notFound).fold({ "ok" }, { it.code.value })).isEqualTo("not-found")
    }
}
