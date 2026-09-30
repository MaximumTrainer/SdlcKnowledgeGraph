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
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Instant
import java.util.UUID

/**
 * A node's earlier values, kept as versions when a write changes them (#33, FR1), against a real
 * Neo4j: the version is cut inside the write's own statement, which only the database can prove.
 *
 * Each test works under names of its own, so what other tests wrote is never in scope.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jGraphStoreVersioningIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var factLifecycle: FactLifecycle

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private val suffix = UUID.randomUUID().toString()
    private val january = Instant.parse("2026-01-01T00:00:00Z")
    private val midJanuary = Instant.parse("2026-01-15T00:00:00Z")
    private val february = Instant.parse("2026-02-01T00:00:00Z")
    private val midFebruary = Instant.parse("2026-02-15T00:00:00Z")
    private val march = Instant.parse("2026-03-01T00:00:00Z")
    private val midMarch = Instant.parse("2026-03-15T00:00:00Z")

    private fun at(instant: Instant) = Provenance(sourceSystem = "manual", ingestedAt = instant, validFrom = instant)

    private val team = NodeKey("Team", "versioned-$suffix")

    private fun stateTeam(
        tier: String,
        instant: Instant,
    ) = graphStore.upsertNode(GraphNode(team, mapOf("name" to team.key, "email" to "$tier@acme.example"), at(instant)))

    /** The tier each test states, carried in a declared property: the part of the email before the @. */
    private fun Map<String, Any?>.tier(): String? = this["email"]?.toString()?.substringBefore("@")

    private fun versionCount(key: NodeKey): Long =
        neo4jClient
            .query("MATCH (v:NodeVersion { versionOf: \$id }) RETURN count(v)")
            .bindAll(mapOf("id" to key.id))
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)

    @Test
    fun `a changed property keeps the value it replaced, readable as of an instant before the change`() {
        stateTeam("gold", january)
        stateTeam("silver", february)

        assertThat(graphStore.findNode(team)!!.props.tier()).isEqualTo("silver")
        assertThat(graphStore.findNode(team, midJanuary)!!.props.tier()).isEqualTo("gold")
        assertThat(graphStore.findNode(team, midFebruary)!!.props.tier()).isEqualTo("silver")
        assertThat(graphStore.findNode(team, Instant.parse("2025-12-01T00:00:00Z"))).isNull()
    }

    @Test
    fun `the current view reads as it always did, with nothing of the versions in it`() {
        stateTeam("gold", january)
        stateTeam("silver", february)

        val current = graphStore.findNode(team)!!
        assertThat(current.props.keys).doesNotContain("versionOf", "since", "until")
        assertThat(current.provenance.validFrom).describedAs("validFrom is when the fact began").isEqualTo(january)
        assertThat(graphStore.findNodes("Team", mapOf("name" to team.key)).map { it.key }).containsOnlyOnce(team)
    }

    @Test
    fun `stating the same values again adds no version`() {
        stateTeam("gold", january)
        stateTeam("gold", february)
        stateTeam("gold", march)

        assertThat(versionCount(team)).isZero()
    }

    @Test
    fun `each change is a version of its own, and the history lists them newest first`() {
        stateTeam("gold", january)
        stateTeam("silver", february)
        stateTeam("bronze", march)

        val history = factLifecycle.history(team)!!
        assertThat(history.current.props.tier()).isEqualTo("bronze")
        assertThat(history.current.propsFrom).isEqualTo(march)
        assertThat(history.versions.map { it.props.tier() }).containsExactly("silver", "gold")
        assertThat(history.versions.map { it.validFrom to it.validTo }).containsExactly(february to march, january to february)
        assertThat(graphStore.findNode(team, midMarch)!!.props.tier()).isEqualTo("bronze")
    }

    @Test
    fun `a retired node stated again comes back, and its history keeps the retirement`() {
        stateTeam("gold", january)
        factLifecycle.retire(team, february, RetiredReason.MISSING_FROM_SYNC)
        stateTeam("gold", march)

        val history = factLifecycle.history(team)!!
        assertThat(history.current.validTo).isNull()
        assertThat(history.current.resurrectedAt).isEqualTo(march)
        assertThat(history.current.retiredReason).isNull()
        val retired = history.versions.single()
        assertThat(retired.retired).isTrue()
        assertThat(retired.retiredReason).isEqualTo(RetiredReason.MISSING_FROM_SYNC)
        assertThat(retired.validFrom to retired.validTo).isEqualTo(january to february)
        assertThat(graphStore.findNode(team, midJanuary)).isNotNull
        assertThat(graphStore.findNode(team, midFebruary)).describedAs("retired between").isNull()
    }

    @Test
    fun `an edge read as of an instant sees its far end's values then`() {
        val repo = NodeKey("Repository", "github.com/versioned/$suffix")
        graphStore.upsertNode(
            GraphNode(
                repo,
                mapOf(
                    "url" to "https://${repo.key}",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
                at(january),
            ),
        )
        stateTeam("gold", january)
        graphStore.upsertEdge(GraphEdge("OWNED_BY", repo, team, emptyMap(), at(january)))
        stateTeam("silver", february)

        val then = graphStore.findEdges(repo, Direction.OUTGOING, "OWNED_BY", midJanuary).single()
        assertThat(then.other.props.tier()).isEqualTo("gold")
        assertThat(
            graphStore
                .findEdges(repo, Direction.OUTGOING, "OWNED_BY")
                .single()
                .other.props
                .tier(),
        ).isEqualTo("silver")
    }

    @Test
    fun `a meta type keeps no versions`() {
        val run = NodeKey("SyncRun", "versioning-$suffix")
        graphStore.upsertNode(GraphNode(run, mapOf("id" to run.key, "status" to "RUNNING"), at(january)))
        graphStore.upsertNode(GraphNode(run, mapOf("id" to run.key, "status" to "SUCCESS"), at(february)))

        assertThat(versionCount(run)).isZero()
    }

    @Test
    fun `deleting a node takes its versions with it`() {
        stateTeam("gold", january)
        stateTeam("silver", february)

        graphStore.deleteNode(team, cascade = true)

        assertThat(versionCount(team)).isZero()
    }
}
