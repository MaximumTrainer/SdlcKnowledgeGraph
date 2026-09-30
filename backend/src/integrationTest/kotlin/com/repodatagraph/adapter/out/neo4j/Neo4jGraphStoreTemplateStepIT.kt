package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourStep
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
 * One step of a context pack's template (#96) against a real Neo4j: the neighbourhood step (#9)
 * narrowed by an edge property the template names - DEPENDED_ON_BY{data} - and read as of an
 * instant, with the far node's values as they were then (#93, #33). The property filter is bound as
 * a parameter, so no template value ever reaches the query text.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jGraphStoreTemplateStepIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    private val run = "step-" + UUID.randomUUID().toString().take(8)
    private val september = Instant.parse("2026-09-01T00:00:00Z")
    private val october = Instant.parse("2026-10-01T00:00:00Z")

    private fun valid(
        from: Instant = september,
        to: Instant? = null,
    ) = Provenance(sourceSystem = "manual", ingestedAt = from, validFrom = from, validTo = to)

    private fun repository(
        name: String,
        provenance: Provenance = valid(),
        description: String = "$name as first written",
    ): NodeKey {
        val key = NodeKey("Repository", "github.com/$run/$name")
        graphStore.upsertNode(
            GraphNode(
                key,
                mapOf(
                    "url" to "https://github.com/$run/$name",
                    "host" to "github.com",
                    "org" to run,
                    "name" to name,
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                    "description" to description,
                ),
                provenance,
            ),
        )
        return key
    }

    private fun bucket(): NodeKey {
        val key = NodeKey("CloudResource", "aws:arn:aws:s3:::$run")
        graphStore.upsertNode(
            GraphNode(
                key,
                mapOf("provider" to "aws", "resourceId" to "arn:aws:s3:::$run", "resourceType" to "s3-bucket", "name" to run),
                valid(),
            ),
        )
        return key
    }

    private fun dependsOn(
        from: NodeKey,
        to: NodeKey,
        kind: String,
        provenance: Provenance = valid(),
    ) = graphStore.upsertEdge(GraphEdge("DEPENDS_ON", from, to, mapOf("kind" to kind), provenance))

    private fun step(
        where: Map<String, Any?> = emptyMap(),
        asOf: Instant? = null,
    ) = NeighbourStep(
        edgeTypes = setOf("DEPENDS_ON"),
        nodeTypes = emptySet(),
        direction = Direction.INCOMING,
        limit = 100,
        where = where,
        asOf = asOf,
    )

    @Test
    fun `a step narrowed by an edge property finds only the edges that hold it`() {
        val bucket = bucket()
        val reader = repository("reader")
        val caller = repository("caller")
        dependsOn(reader, bucket, "data")
        dependsOn(caller, bucket, "api")

        val found = graphStore.neighbourhood(listOf(bucket), step(where = mapOf("kind" to "data")))

        assertThat(found.hops.map { it.other.key }).containsExactly(reader)
        assertThat(
            found.hops
                .single()
                .edge.props,
        ).containsEntry("kind", "data")
        assertThat(found.hops.single().direction).isEqualTo(Direction.INCOMING)
        assertThat(graphStore.neighbourhood(listOf(bucket), step()).hops.map { it.other.key }).containsExactlyInAnyOrder(reader, caller)
    }

    @Test
    fun `a filter value is data, never query text`() {
        val bucket = bucket()
        dependsOn(repository("reader"), bucket, "data")

        val found = graphStore.neighbourhood(listOf(bucket), step(where = mapOf("kind" to "data' OR 1=1 //")))

        assertThat(found.hops).isEmpty()
    }

    @Test
    fun `as of an instant, a step finds the edges and far nodes that held then, with the values they had`() {
        val bucket = bucket()
        val early = repository("early")
        val late = repository("late", provenance = valid(from = october))
        val gone = repository("gone")
        dependsOn(early, bucket, "data")
        dependsOn(late, bucket, "data", provenance = valid(from = october))
        dependsOn(gone, bucket, "data", provenance = valid(to = Instant.parse("2026-09-15T00:00:00Z")))
        // A later write replaces early's description; as of September it still had the first.
        repository("early", provenance = valid(from = october), description = "rewritten")

        val september20 = Instant.parse("2026-09-20T00:00:00Z")
        val then = graphStore.neighbourhood(listOf(bucket), step(where = mapOf("kind" to "data"), asOf = september20))

        assertThat(then.hops.map { it.other.key }).containsExactly(early)
        assertThat(
            then.hops
                .single()
                .other.props["description"],
        ).isEqualTo("early as first written")

        val now = graphStore.neighbourhood(listOf(bucket), step(where = mapOf("kind" to "data")))
        assertThat(now.hops.map { it.other.key }).containsExactlyInAnyOrder(early, late)
    }
}
