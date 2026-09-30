package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
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
 * Reads as of an instant, and the validity a restated fact keeps (#93, FR-4), against a real Neo4j:
 * the interval is compared on stored temporal values in Cypher, which an in-memory store could not
 * prove.
 *
 * Each test works under names of its own, so what other tests wrote is never in scope.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jGraphStoreAsOfIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    private val suffix = UUID.randomUUID().toString()
    private val environment = NodeKey("Environment", "asof-$suffix")
    private val one = Instant.parse("2026-09-30T01:00:00Z")
    private val twoMinutesToTwo = Instant.parse("2026-09-30T01:58:00Z")
    private val halfPastOne = Instant.parse("2026-09-30T01:30:00Z")
    private val halfPastTwo = Instant.parse("2026-09-30T02:30:00Z")

    private fun valid(
        from: Instant,
        to: Instant? = null,
    ) = Provenance(sourceSystem = "manual", ingestedAt = from, validFrom = from, validTo = to)

    private fun team(
        name: String,
        provenance: Provenance,
    ): NodeKey {
        val key = NodeKey("Team", "asof-$name-$suffix")
        graphStore.upsertNode(GraphNode(key, mapOf("name" to key.key), provenance))
        return key
    }

    private fun deployment(
        name: String,
        from: Instant,
        to: Instant?,
    ): NodeKey {
        val key = NodeKey("Deployment", "asof-$name-$suffix")
        graphStore.upsertNode(
            GraphNode(
                key,
                mapOf("deployedAt" to from, "artifactId" to name, "environmentId" to environment.key, "status" to "SUCCESS"),
                valid(from, to),
            ),
        )
        graphStore.upsertEdge(GraphEdge("TO_ENVIRONMENT", key, environment, emptyMap(), valid(from, to)))
        return key
    }

    @Test
    fun `a node is found as of an instant inside its validity, and not outside it`() {
        val closed = team("closed", valid(one, twoMinutesToTwo))

        assertThat(graphStore.findNode(closed, halfPastOne)).isNotNull
        assertThat(graphStore.findNode(closed, one)).isNotNull
        assertThat(graphStore.findNode(closed, twoMinutesToTwo)).isNull()
        assertThat(graphStore.findNode(closed, halfPastTwo)).isNull()
        assertThat(graphStore.findNode(closed, Instant.parse("2026-09-30T00:00:00Z"))).isNull()
        // The current view is what it always was: a closed node is still found.
        assertThat(graphStore.findNode(closed)).isNotNull
    }

    @Test
    fun `an open node is found as of any instant from when it began`() {
        val open = team("open", valid(one))

        assertThat(graphStore.findNode(open, Instant.parse("2099-01-01T00:00:00Z"))).isNotNull
        assertThat(graphStore.findNode(open, Instant.parse("2026-09-29T00:00:00Z"))).isNull()
    }

    @Test
    fun `the edges of a node as of an instant are those valid then, with an end valid then`() {
        graphStore.upsertNode(GraphNode(environment, mapOf("name" to environment.key, "type" to "production"), valid(one)))
        val a = deployment("a", one, twoMinutesToTwo)
        val b = deployment("b", twoMinutesToTwo, null)

        fun others(asOf: Instant) = graphStore.findEdges(environment, Direction.BOTH, null, asOf).map { it.other.key }

        assertThat(others(halfPastOne)).containsExactly(a)
        assertThat(others(halfPastTwo)).containsExactly(b)
        assertThat(graphStore.findEdges(environment, Direction.BOTH, null).map { it.other.key }).containsExactlyInAnyOrder(a, b)
        assertThat(graphStore.findEdges(environment, Direction.INCOMING, "TO_ENVIRONMENT", halfPastOne).map { it.other.key })
            .containsExactly(a)
    }

    @Test
    fun `restating a current node keeps the validFrom it began with`() {
        val key = team("restated", valid(one))

        graphStore.upsertNode(GraphNode(key, mapOf("name" to key.key), valid(halfPastTwo)))

        val stored = graphStore.findNode(key)!!.provenance
        assertThat(stored.validFrom).isEqualTo(one)
        assertThat(stored.ingestedAt).isEqualTo(halfPastTwo)
    }

    @Test
    fun `restating a closed node as current begins it again`() {
        val key = team("reopened", valid(one, twoMinutesToTwo))

        graphStore.upsertNode(GraphNode(key, mapOf("name" to key.key), valid(halfPastTwo)))

        val stored = graphStore.findNode(key)!!.provenance
        assertThat(stored.validFrom).isEqualTo(halfPastTwo)
        assertThat(stored.validTo).isNull()
    }

    @Test
    fun `restating a current edge keeps the validFrom it began with`() {
        graphStore.upsertNode(GraphNode(environment, mapOf("name" to environment.key, "type" to "production"), valid(one)))
        val a = deployment("restated", one, null)

        graphStore.upsertEdge(GraphEdge("TO_ENVIRONMENT", a, environment, emptyMap(), valid(halfPastTwo)))

        val stored = graphStore.findEdge("TO_ENVIRONMENT", a, environment)!!.provenance
        assertThat(stored.validFrom).isEqualTo(one)
        assertThat(stored.ingestedAt).isEqualTo(halfPastTwo)
    }
}
