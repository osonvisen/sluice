package no.orjans.sluice.shared

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** For JDBC: the Postgres driver binds `OffsetDateTime` to `timestamptz`, but not `Instant`. Always UTC. */
fun Instant.atUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
