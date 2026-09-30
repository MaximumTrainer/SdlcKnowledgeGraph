package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Instant
import java.util.UUID

/**
 * The provider id alias against a real Neo4j (#88): the constraint the schema initializer creates
 * from the registry, the lookups by alias and by a key a node has left, and the rename that moves a
 * node to a new key without losing its edges or the keys it had.
 *
 * The constraint is the database's to enforce, so it is proved here rather than in a unit test: an
 * application check alone loses the race between two connectors reporting the same repository.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jRepositoryAliasIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    /** Unique per test, so the ids and keys other tests wrote to the same database never collide. */
    private val run = "it-" + UUID.randomUUID().toString().take(8)
    private val at = Instant.parse("2026-09-30T10:00:00Z")

    @AfterEach
    fun removeWhatThisTestWrote() {
        neo4jClient.query("MATCH (n) WHERE n.key CONTAINS \$run DETACH DELETE n").bindAll(mapOf("run" to run)).run()
    }

    private fun repository(
        name: String,
        provider: String? = "github",
        providerId: String? = "$run-1",
    ): GraphNode {
        val key = NodeKey("Repository", "github.com/acme/$run-$name")
        val alias = if (providerId != null) mapOf("provider" to provider, "providerId" to providerId) else emptyMap()
        return GraphNode(key, mapOf("url" to "https://${key.key}", "name" to "$run-$name") + alias, Provenance.manual(at))
    }

    private fun constraints(): List<Map<String, Any?>> =
        neo4jClient
            .query("SHOW CONSTRAINTS YIELD name, labelsOrTypes, properties, type RETURN name, labelsOrTypes, properties, type")
            .fetch()
            .all()
            .toList()

    @Test
    fun `the schema holds a repository's provider and provider id unique together`() {
        val alias =
            constraints().filter {
                it["labelsOrTypes"] == listOf("Repository") &&
                    it["properties"] == listOf("provider", "providerId")
            }

        assertThat(alias).hasSize(1)
        assertThat(alias.single()["type"]).isEqualTo("UNIQUENESS")
    }

    @Test
    fun `a second repository with the same provider id is refused by the database`() {
        graphStore.upsertNode(repository("payments"))

        assertThatThrownBy { graphStore.upsertNode(repository("other")) }
            .hasMessageContaining("already exists")
    }

    @Test
    fun `the same id at another provider, or no id at all, is not a collision`() {
        graphStore.upsertNode(repository("payments"))
        graphStore.upsertNode(repository("mirror", provider = "gitlab"))
        graphStore.upsertNode(repository("first", providerId = null))
        graphStore.upsertNode(repository("second", providerId = null))

        assertThat(graphStore.findNodeByAlias("Repository", mapOf("provider" to "gitlab", "providerId" to "$run-1"))?.key?.key)
            .isEqualTo("github.com/acme/$run-mirror")
    }

    @Test
    fun `a repository is found by its alias, and nothing by an alias no node holds`() {
        graphStore.upsertNode(repository("payments"))

        assertThat(graphStore.findNodeByAlias("Repository", mapOf("provider" to "github", "providerId" to "$run-1"))?.key?.key)
            .isEqualTo("github.com/acme/$run-payments")
        assertThat(graphStore.findNodeByAlias("Repository", mapOf("provider" to "github", "providerId" to "$run-2"))).isNull()
    }

    @Test
    fun `a rename moves the node to its new key with its edges, and remembers the key it left`() {
        val before = repository("payments")
        val team = NodeKey("Team", "$run-billing")
        graphStore.upsertNode(before)
        graphStore.upsertNode(GraphNode(team, mapOf("name" to team.key), Provenance.manual(at)))
        graphStore.upsertEdge(GraphEdge("OWNED_BY", before.key, team, provenance = Provenance.manual(at)))
        val after = repository("payments-service")

        val renamed =
            graphStore.renameNode(
                before.key,
                after.copy(provenance = after.provenance.copy(previousKeys = listOf(before.key.key))),
            )

        assertThat(renamed.key).isEqualTo(after.key)
        assertThat(graphStore.findNode(before.key)).isNull()
        val found = graphStore.findNode(after.key)
        assertThat(found?.id).isEqualTo(after.key.id)
        assertThat(found?.props?.get("name")).isEqualTo("$run-payments-service")
        assertThat(found?.provenance?.previousKeys).containsExactly(before.key.key)
        assertThat(graphStore.findEdge("OWNED_BY", after.key, team)).isNotNull()
        assertThat(graphStore.findNodeByPreviousKey(before.key)?.key).isEqualTo(after.key)
    }

    /** A later write states its own provenance; the keys a node had are history it must not erase. */
    @Test
    fun `a write after a rename keeps the keys the node had`() {
        val before = repository("payments")
        val after = repository("payments-service")
        graphStore.upsertNode(before)
        graphStore.renameNode(before.key, after.copy(provenance = after.provenance.copy(previousKeys = listOf(before.key.key))))

        graphStore.upsertNode(after.copy(props = after.props + ("description" to "restated"), provenance = Provenance.manual(at)))

        assertThat(graphStore.findNode(after.key)?.provenance?.previousKeys).containsExactly(before.key.key)
    }

    @Test
    fun `a key no node has held is found by no one`() {
        assertThat(graphStore.findNodeByPreviousKey(NodeKey("Repository", "github.com/acme/$run-never"))).isNull()
    }
}
