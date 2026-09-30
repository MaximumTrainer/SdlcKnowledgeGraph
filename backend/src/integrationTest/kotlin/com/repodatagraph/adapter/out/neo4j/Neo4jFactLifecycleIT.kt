package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
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

    private fun reason(key: NodeKey): RetiredReason? = factLifecycle.history(key)!!.current.retiredReason

    private fun ownedBy(
        repo: NodeKey,
        team: NodeKey,
    ) = graphStore.upsertEdge(
        GraphEdge("OWNED_BY", repo, team, emptyMap(), Provenance(sourceSystem = source, ingestedAt = before, validFrom = before)),
    )

    private fun repository(name: String): NodeKey {
        val key = NodeKey("Repository", "github.com/$source/$name")
        graphStore.upsertNode(
            GraphNode(
                key,
                mapOf(
                    "url" to "https://${key.key}",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
                Provenance(sourceSystem = source, ingestedAt = before, validFrom = before),
            ),
        )
        return key
    }

    @Test
    fun `reconciling records why it closed a fact, and closes the fact's current edges`() {
        val team = team("owner", ingestedAt = during)
        val repo = repository("gone")
        ownedBy(repo, team)

        factLifecycle.closeNodesNotReasserted(source, runStarted, closedAt, RetiredReason.MISSING_FROM_SYNC)

        assertThat(reason(repo)).isEqualTo(RetiredReason.MISSING_FROM_SYNC)
        val edge = graphStore.findEdge("OWNED_BY", repo, team)!!
        assertThat(edge.provenance.validTo).isEqualTo(closedAt)
    }

    @Test
    fun `retiring one node closes it and its current edges, and leaves a closed edge's date alone`() {
        val team = team("owner", ingestedAt = during)
        val repo = repository("deleted")
        ownedBy(repo, team)

        assertThat(factLifecycle.retire(repo, closedAt, RetiredReason.SOURCE_DELETED)).isTrue()

        assertThat(validTo(repo)).isEqualTo(closedAt)
        assertThat(reason(repo)).isEqualTo(RetiredReason.SOURCE_DELETED)
        assertThat(
            graphStore
                .findEdges(repo, Direction.OUTGOING, "OWNED_BY")
                .single()
                .edge.provenance.validTo,
        ).isEqualTo(closedAt)
        assertThat(validTo(team)).describedAs("the other end is not retired").isNull()

        factLifecycle.retire(repo, Instant.parse("2026-09-02T00:00:00Z"), RetiredReason.SOURCE_DELETED)
        assertThat(validTo(repo)).describedAs("retiring again moves nothing").isEqualTo(closedAt)
    }

    @Test
    fun `retiring a node the graph never held is a no`() {
        assertThat(factLifecycle.retire(NodeKey("Team", "$source-never"), closedAt, RetiredReason.SOURCE_DELETED)).isFalse()
    }
}
