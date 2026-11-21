package no.orjans.sluice.features.users

import no.orjans.sluice.shared.TypedId
import java.util.UUID

/** Our own ID for a person in `app_users`, so other tables (e.g. an audit log) never store the identity provider's subject. */
@JvmInline
value class UserId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): UserId = UserId(UUID.randomUUID())
    }
}
