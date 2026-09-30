package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.identity.EdgeMoves
import com.repodatagraph.domain.identity.MergePlan
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.NodeMergeStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * Merging one node into another against a real Neo4j (#98, FR-4): every edge moves in one
 * transaction, the source is retired as merged with a pointer rather than deleted, the target takes
 * what it lacked and records the key it absorbed, a dry run leaves nothing behind, and a failure
 * part-way leaves the graph as it was. Afterwards a write addressed at the merged key cannot bring
 * the duplicate back: a node write to it changes nothing, and an edge to it lands on the target.
 *
 * Each test works under names of its own, so what other tests wrote is never in scope.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jNodeMergeStoreIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var mergeStore: NodeMergeStore

    @Autowired
    private lateinit var factLifecycle: FactLifecycle

    private val suffix = UUID.randomUUID().toString().take(8)
    private val january = Instant.parse("2026-01-01T00:00:00Z")
    private val merging = Instant.parse("2026-03-01T00:00:00Z")

    private val source = NodeKey("Repository", "github.com/acme-$suffix/payments")
    private val target = NodeKey("Repository", "github.com/acme-$suffix/payments-service")
    private val team = NodeKey("Team", "billing-$suffix")
    private val other = NodeKey("Team", "platform-$suffix")
    private val dependent = NodeKey("Repository", "github.com/acme-$suffix/checkout")

    private val by =
        Provenance(sourceSystem = "manual", ingestedAt = merging, validFrom = merging, writtenBy = "dan", principalType = "user")

    private fun stated(instant: Instant = january) = Provenance(sourceSystem = "github", ingestedAt = instant, validFrom = instant)

    private fun repository(
        key: NodeKey,
        extra: Map<String, Any?> = emptyMap(),
    ): GraphNode {
        val (host, org, name) = key.key.split('/')
        val node =
            GraphNode(
                key,
                mapOf(
                    "url" to "https://${key.key}",
                    "host" to host,
                    "org" to org,
                    "name" to name,
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ) + extra,
                stated(),
            )
        return graphStore.upsertNode(node)
    }

    private fun team(key: NodeKey) = graphStore.upsertNode(GraphNode(key, mapOf("name" to key.key), stated()))

    private fun edge(
        type: String,
        from: NodeKey,
        to: NodeKey,
        props: Map<String, Any?> = emptyMap(),
    ) = graphStore.upsertEdge(GraphEdge(type, from, to, props, stated()))

    private fun plan(
        gained: Map<String, Any?> = emptyMap(),
        released: List<String> = emptyList(),
    ) = MergePlan(
        source = graphStore.findNode(source)!!,
        target = graphStore.findNode(target)!!,
        gained = gained,
        kept = emptyList(),
        released = released,
        previousKeys = listOf(source.key),
        by = by,
    )

    private fun edgeTypes(key: NodeKey): List<String> =
        graphStore.findEdges(key, Direction.BOTH).map { "${it.direction} ${it.edge.type} ${it.other.key.key}" }.sorted()

    @Test
    fun `every edge of the source moves to the target, in both directions, with its properties and provenance`() {
        repository(source)
        repository(target)
        repository(dependent)
        team(team)
        edge("OWNED_BY", source, team)
        edge("DEPENDS_ON", dependent, source, mapOf("kind" to "library", "manifest" to "services/checkout/build.gradle.kts"))

        val result = mergeStore.merge(plan(), dryRun = false)

        assertThat(result.edges).isEqualTo(EdgeMoves(moved = 2, collapsed = 0, dropped = 0))
        assertThat(edgeTypes(source)).isEmpty()
        assertThat(edgeTypes(target)).containsExactly("INCOMING DEPENDS_ON ${dependent.key}", "OUTGOING OWNED_BY ${team.key}")
        val moved = graphStore.findEdge("DEPENDS_ON", dependent, target)!!
        assertThat(moved.props).containsEntry("manifest", "services/checkout/build.gradle.kts")
        assertThat(moved.provenance.sourceSystem).isEqualTo("github")
        assertThat(moved.provenance.validFrom).isEqualTo(january)
    }

    @Test
    fun `an edge the target already has keeps the target's, and an edge between the two is dropped`() {
        repository(source)
        repository(target)
        team(team)
        edge("OWNED_BY", source, team)
        edge("OWNED_BY", target, team)
        edge("DEPENDS_ON", source, target, mapOf("kind" to "api"))

        val result = mergeStore.merge(plan(), dryRun = false)

        assertThat(result.edges).isEqualTo(EdgeMoves(moved = 0, collapsed = 1, dropped = 1))
        assertThat(edgeTypes(target)).containsExactly("OUTGOING OWNED_BY ${team.key}")
        assertThat(edgeTypes(source)).isEmpty()
    }

    @Test
    fun `the source is retired as merged, pointing at the target and naming who merged it, not deleted`() {
        repository(source)
        repository(target)

        mergeStore.merge(plan(), dryRun = false)

        val retired = graphStore.findNode(source)!!
        assertThat(retired.provenance.validTo).isEqualTo(merging)
        assertThat(retired.provenance.writtenBy).isEqualTo("dan")
        assertThat(graphStore.mergedInto(source)).isEqualTo(target)
        val history = factLifecycle.history(source)!!
        assertThat(history.current.retiredReason).isEqualTo(RetiredReason.MERGED)
        assertThat(history.current.mergedInto).isEqualTo(target)
        assertThat(history.current.mergedBy).isEqualTo("dan")
    }

    @Test
    fun `the target takes what it lacked and the key it absorbed, keeping what it replaced as a version`() {
        repository(source, mapOf("language" to "Kotlin"))
        repository(target)

        val result = mergeStore.merge(plan(gained = mapOf("language" to "Kotlin")), dryRun = false)

        val merged = graphStore.findNode(target)!!
        assertThat(result.node.key).isEqualTo(target)
        assertThat(merged.props).containsEntry("language", "Kotlin")
        assertThat(merged.provenance.previousKeys).containsExactly(source.key)
        assertThat(merged.provenance.validTo).isNull()
        val versions = factLifecycle.history(target)!!.versions
        assertThat(versions).hasSize(1)
        assertThat(versions.single().props).doesNotContainKey("language")
        assertThat(graphStore.findNode(target, Instant.parse("2026-02-01T00:00:00Z"))!!.props).doesNotContainKey("language")
    }

    @Test
    fun `an alias the target takes over is given up by the source in the same transaction`() {
        repository(source, mapOf("provider" to "github", "providerId" to "id-$suffix"))
        repository(target)

        mergeStore.merge(
            plan(gained = mapOf("provider" to "github", "providerId" to "id-$suffix"), released = listOf("provider", "providerId")),
            dryRun = false,
        )

        assertThat(graphStore.findNodeByAlias("Repository", mapOf("provider" to "github", "providerId" to "id-$suffix"))!!.key)
            .isEqualTo(target)
        assertThat(graphStore.findNode(source)!!.props).doesNotContainKeys("provider", "providerId")
    }

    @Test
    fun `a node merged into the source earlier now points at the target`() {
        val earlier = NodeKey("Repository", "github.com/acme-$suffix/pay")
        repository(earlier)
        repository(source)
        repository(target)
        mergeStore.merge(
            MergePlan(
                graphStore.findNode(earlier)!!,
                graphStore.findNode(source)!!,
                emptyMap(),
                emptyList(),
                emptyList(),
                listOf(earlier.key),
                by,
            ),
            dryRun = false,
        )

        val result = mergeStore.merge(plan().copy(previousKeys = listOf(earlier.key, source.key)), dryRun = false)

        assertThat(result.redirected).isEqualTo(1)
        assertThat(graphStore.mergedInto(earlier)).isEqualTo(target)
    }

    @Test
    fun `a dry run reports what the merge would do and changes nothing`() {
        repository(source, mapOf("language" to "Kotlin"))
        repository(target)
        team(team)
        edge("OWNED_BY", source, team)

        val preview = mergeStore.merge(plan(gained = mapOf("language" to "Kotlin")), dryRun = true)

        assertThat(preview.edges.moved).isEqualTo(1)
        assertThat(preview.node.props).containsEntry("language", "Kotlin")
        assertThat(edgeTypes(source)).containsExactly("OUTGOING OWNED_BY ${team.key}")
        assertThat(edgeTypes(target)).isEmpty()
        assertThat(graphStore.findNode(source)!!.provenance.validTo).isNull()
        assertThat(graphStore.mergedInto(source)).isNull()
        assertThat(graphStore.findNode(target)!!.props).doesNotContainKey("language")
    }

    @Test
    fun `a merge that fails part-way leaves the graph exactly as it was`() {
        repository(NodeKey("Repository", "github.com/acme-$suffix/holder"), mapOf("provider" to "github", "providerId" to "taken-$suffix"))
        repository(source)
        repository(target)
        team(team)
        edge("OWNED_BY", source, team)

        // The target cannot take an alias another node holds: the constraint refuses it mid-merge.
        assertThatThrownBy {
            mergeStore.merge(
                plan(gained = mapOf("provider" to "github", "providerId" to "taken-$suffix")),
                dryRun = false,
            )
        }

        assertThat(edgeTypes(source)).containsExactly("OUTGOING OWNED_BY ${team.key}")
        assertThat(graphStore.findNode(source)!!.provenance.validTo).isNull()
        assertThat(graphStore.findNode(target)!!.props).doesNotContainKey("providerId")
    }

    @Test
    fun `a later write of the merged key changes nothing, and an edge to it lands on the target`() {
        repository(source)
        repository(target)
        team(other)
        mergeStore.merge(plan(), dryRun = false)

        graphStore.upsertNode(
            GraphNode(
                source,
                graphStore.findNode(source)!!.props + ("description" to "back again"),
                stated(Instant.parse("2026-04-01T00:00:00Z")),
            ),
        )
        edge("OWNED_BY", source, other)

        val retired = graphStore.findNode(source)!!
        assertThat(retired.provenance.validTo).isEqualTo(merging)
        assertThat(retired.props).doesNotContainKey("description")
        assertThat(edgeTypes(source)).isEmpty()
        assertThat(edgeTypes(target)).containsExactly("OUTGOING OWNED_BY ${other.key}")
    }

    @Test
    fun `a node never merged points nowhere`() {
        repository(source)

        assertThat(graphStore.mergedInto(source)).isNull()
        assertThat(graphStore.mergedInto(NodeKey("Repository", "github.com/acme-$suffix/nothing"))).isNull()
    }
}
