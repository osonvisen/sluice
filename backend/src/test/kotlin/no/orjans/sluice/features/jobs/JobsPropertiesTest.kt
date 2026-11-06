package no.orjans.sluice.features.jobs

import no.orjans.sluice.support.FixedRandom
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class JobsPropertiesTest {
    private fun properties(
        initial: Duration = Duration.ofSeconds(30),
        max: Duration = Duration.ofMinutes(5),
        jitter: Double = 0.2,
    ) = JobsProperties(
        maxAttempts = 5,
        initialBackoff = initial,
        maxBackoff = max,
        backoffJitter = jitter,
        lease = Duration.ofMinutes(5),
        batchSize = 10,
        worker = JobsProperties.Worker(enabled = false, pollInterval = Duration.ofSeconds(2)),
    )

    private fun random(value: Double) = FixedRandom().apply { this.value = value }

    @Test
    fun `backoff doubles after each failed attempt`() {
        val p = properties()

        assertThat((1..4).map { p.backoffAfter(it, random(FixedRandom.MIDDLE)).seconds }).containsExactly(30, 60, 120, 240)
    }

    @Test
    fun `jitter spreads the backoff by the configured fraction either way`() {
        val p = properties(jitter = 0.2)

        assertThat(p.backoffAfter(1, random(0.0))).isEqualTo(Duration.ofSeconds(24))
        assertThat(p.backoffAfter(1, random(0.999_999))).isBetween(Duration.ofMillis(35_999), Duration.ofSeconds(36))
    }

    @Test
    fun `zero jitter gives the exact exponential backoff`() {
        assertThat(properties(jitter = 0.0).backoffAfter(2, random(0.0))).isEqualTo(Duration.ofSeconds(60))
    }

    @Test
    fun `backoff is capped, also after jitter`() {
        assertThat(properties().backoffAfter(5, random(FixedRandom.MIDDLE))).isEqualTo(Duration.ofMinutes(5))
        assertThat(properties().backoffAfter(5, random(0.999))).isEqualTo(Duration.ofMinutes(5))
        assertThat(properties().backoffAfter(1_000, random(FixedRandom.MIDDLE))).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `invalid configuration fails at startup`() {
        assertThatThrownBy { properties(initial = Duration.ofHours(2), max = Duration.ofHours(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { properties(jitter = 1.0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
