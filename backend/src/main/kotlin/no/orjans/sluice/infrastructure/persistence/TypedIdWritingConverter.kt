package no.orjans.sluice.infrastructure.persistence

import no.orjans.sluice.shared.TypedId
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.WritingConverter
import java.util.UUID

/**
 * Writes any [TypedId] as its UUID. One converter covers every ID type.
 *
 * Needed where Spring Data sees the ID boxed (as the interface), e.g. the argument to
 * `findById`: without it the lookup silently finds nothing. Reading needs no converter,
 * because Spring Data creates Kotlin value classes from the column value itself.
 */
@WritingConverter
object TypedIdWritingConverter : Converter<TypedId, UUID> {
    override fun convert(source: TypedId): UUID = source.value
}
