package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * Reconciliation closes facts in bulk, in one query, so it is proved against a real Neo4j: which
 * nodes it selects is the whole behaviour, and an in-memory store would prove the intention rather
 * than the Cypher (#150).
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jFactLifecycleIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var factLifecycle: FactLifecycle

    /** Unique per test, so nodes written by other tests against the same database are never in scope. */
    private val source = "reconcile-it-" + UUID.randomUUID()
    private val before = Instant.parse("2026-09-01T10:00:00Z")
    private val runStarted = Instant.parse("2026-09-01T11:00:00Z")
    private val during = Instant.parse("2026-09-01T11:05:00Z")
    private val closedAt = Instant.parse("2026-09-01T11:10:00Z")

    private fun team(
        name: String,
        sourceSystem: String = source,
        ingestedAt: Instant = before,
        validTo: Instant? = null,
    ): NodeKey {
        val key = NodeKey("Team", "$source-$name")
        graphStore.upsertNode(
            GraphNode(
                key = key,
                props = mapOf("name" to "$source-$name"),
                provenance =
                    Provenance(
                        sourceSystem = sourceSystem,
                        ingestedAt = ingestedAt,
                        validFrom = ingestedAt,
                        validTo = validTo,
                    ),
            ),
        )
        return key
    }

    private fun validTo(key: NodeKey): Instant? = graphStore.findNode(key)!!.provenance.validTo

    @Test
    fun `closes an open fact this source asserted before the run and not since`() {
        val gone = team("gone")

        val closed = factLifecycle.closeNodesNotReasserted(source, runStarted, closedAt)

        assertThat(closed).isEqualTo(1)
        assertThat(validTo(gone)).isEqualTo(closedAt)
        assertThat(graphStore.findNode(gone)).describedAs("closed, never deleted").isNotNull
    }

    @Test
    fun `leaves alone what the run asserted again`() {
        val kept = team("kept", ingestedAt = during)

        assertThat(factLifecycle.closeNodesNotReasserted(source, runStarted, closedAt)).isZero()
        assertThat(validTo(kept)).isNull()
    }

    @Test
    fun `leaves alone what another source asserted`() {
        val someoneElses = team("manual", sourceSystem = "manual")

        assertThat(factLifecycle.closeNodesNotReasserted(source, runStarted, closedAt)).isZero()
        assertThat(validTo(someoneElses)).isNull()
    }

    @Test
    fun `does not move the date on a fact that was already closed`() {
        val earlier = Instant.parse("2026-09-01T10:30:00Z")
        val alreadyClosed = team("archived", validTo = earlier)

        assertThat(factLifecycle.closeNodesNotReasserted(source, runStarted, closedAt)).isZero()
        assertThat(validTo(alreadyClosed)).isEqualTo(earlier)
    }
}
