package no.orjans.sluice.infrastructure.persistence

import no.orjans.sluice.shared.TypedId
import no.orjans.sluice.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** Round trip of a typed ID through Spring Data JDBC against real Postgres, with a test-only table. */
@IntegrationTest
class TypedIdPersistenceIT {
    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var aggregates: JdbcAggregateTemplate

    @BeforeEach
    fun createTable() {
        jdbc.sql("CREATE TABLE IF NOT EXISTS typed_id_probe (id uuid PRIMARY KEY, parent_id uuid)").update()
    }

    @Test
    fun `typed ids are stored as uuid columns`() {
        val probe = Probe(ProbeId.new(), parentId = null)

        aggregates.insert(probe)

        val stored =
            jdbc
                .sql("SELECT id FROM typed_id_probe WHERE id = ?")
                .param(probe.id.value)
                .query(UUID::class.java)
                .single()
        assertThat(stored).isEqualTo(probe.id.value)
    }

    @Test
    fun `typed ids are read back, including nullable references`() {
        val probe = Probe(ProbeId.new(), parentId = ProbeId.new())
        aggregates.insert(probe)

        assertThat(aggregates.findById(probe.id, Probe::class.java)).isEqualTo(probe)
    }

    @Test
    fun `lookup by a typed id that does not exist finds nothing`() {
        assertThat(aggregates.findById(ProbeId.new(), Probe::class.java)).isNull()
    }

    @JvmInline
    value class ProbeId private constructor(
        override val value: UUID,
    ) : TypedId {
        companion object {
            fun new(): ProbeId = ProbeId(UUID.randomUUID())
        }
    }

    @Table("typed_id_probe")
    data class Probe(
        @Id val id: ProbeId,
        val parentId: ProbeId?,
    )
}
