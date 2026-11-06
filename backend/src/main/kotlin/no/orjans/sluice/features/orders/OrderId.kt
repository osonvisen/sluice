package no.orjans.sluice.features.orders

import no.orjans.sluice.shared.TypedId
import java.util.UUID

@JvmInline
value class OrderId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): OrderId = OrderId(UUID.randomUUID())

        fun of(value: UUID): OrderId = OrderId(value)
    }
}
