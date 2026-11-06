package no.orjans.sluice.features.jobs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class JobsPropertiesTest {
    private fun properties(
        initial: Duration = Duration.ofSeconds(30),
        max: Duration = Duration.ofMinutes(5),
    ) = JobsProperties(
        maxAttempts = 5,
        initialBackoff = initial,
        maxBackoff = max,
        lease = Duration.ofMinutes(5),
        batchSize = 10,
        worker = JobsProperties.Worker(enabled = false, pollInterval = Duration.ofSeconds(2)),
    )

    @Test
    fun `backoff doubles after each failed attempt`() {
        val p = properties()

        assertThat((1..4).map { p.backoffAfter(it).seconds }).containsExactly(30, 60, 120, 240)
    }

    @Test
    fun `backoff is capped`() {
        assertThat(properties().backoffAfter(5)).isEqualTo(Duration.ofMinutes(5))
        assertThat(properties().backoffAfter(1_000)).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `invalid configuration fails at startup`() {
        assertThatThrownBy { properties(initial = Duration.ofHours(2), max = Duration.ofHours(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
