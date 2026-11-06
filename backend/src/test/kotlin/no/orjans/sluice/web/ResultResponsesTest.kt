package no.orjans.sluice.web

import no.orjans.sluice.shared.AppError
import no.orjans.sluice.shared.Result
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ResultResponsesTest {
    @Test
    fun `getOrThrow returns the value of a success`() {
        assertThat(Result.success("ok").getOrThrow()).isEqualTo("ok")
    }

    @Test
    fun `getOrThrow carries the error of a failure, without a stack trace`() {
        val error = AppError.NotFound("job", "42")

        assertThatThrownBy { Result.failure(error).getOrThrow() }
            .isInstanceOfSatisfying(AppErrorException::class.java) { ex ->
                assertThat(ex.error).isEqualTo(error)
                assertThat(ex.stackTrace).isEmpty()
            }
    }
}
