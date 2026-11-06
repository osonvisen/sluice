package no.orjans.sluice.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import java.util.random.RandomGenerator

/** A clock tests can move, so backoff and leases are tested without sleeping. */
class MutableClock(
    start: Instant,
) : Clock() {
    private val now = AtomicReference(start)

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun set(instant: Instant) = now.set(instant)

    fun advance(duration: Duration): Instant = now.updateAndGet { it.plus(duration) }

    companion object {
        val START: Instant = Instant.parse("2026-11-06T09:00:00Z")
    }
}

/**
 * Randomness tests control. The default 0.5 is the middle of the range, which makes backoff jitter
 * zero, so tests can predict `next_run_at` exactly.
 */
class FixedRandom : RandomGenerator {
    @Volatile
    var value: Double = MIDDLE

    override fun nextDouble(): Double = value

    override fun nextLong(): Long = (value * Long.MAX_VALUE).toLong()

    companion object {
        const val MIDDLE = 0.5
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(MutableClock.START)

    @Bean
    @Primary
    fun fixedRandom(): FixedRandom = FixedRandom()
}
