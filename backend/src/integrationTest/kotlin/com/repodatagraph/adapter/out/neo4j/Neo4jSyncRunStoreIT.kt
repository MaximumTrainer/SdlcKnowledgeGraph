package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.SyncRunQuery
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.SyncRunStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Reading and pruning the run history is all Cypher - ordering, paging, filters on stored temporal
 * values, and what a delete may touch - so it is proved against a real Neo4j (#29, FR5 and FR6).
 *
 * Each test writes runs for a connector of its own, so runs other tests wrote are never counted. The
 * prune tests work in 2020, which nothing else writes, and clear it first.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jSyncRunStoreIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var store: SyncRunStore

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private val connector = "runs-it-" + UUID.randomUUID()
    private val base = Instant.parse("2026-09-01T10:00:00Z")
    private val longAgo = Instant.parse("2020-06-01T00:00:00Z")
    private val cutoff = Instant.parse("2021-01-01T00:00:00Z")

    @BeforeEach
    fun forgetOldRuns() {
        neo4jClient
            .query("MATCH (n:SyncRun) WHERE n.startedAt < ${'$'}cutoff DETACH DELETE n")
            .bindAll(mapOf("cutoff" to ProvenanceMapper.storable(cutoff)))
            .run()
    }

    private fun run(
        id: String,
        startedAt: Instant,
        status: String = "SUCCESS",
        finishedAfter: Duration? = Duration.ofSeconds(90),
        error: String? = null,
    ): String {
        val runId = "$connector-$id"
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", runId),
                props =
                    mapOf(
                        "id" to runId,
                        "connector" to connector,
                        "sourceSystem" to "fake",
                        "mode" to "FULL",
                        "status" to status,
                        "startedAt" to startedAt,
                        "finishedAt" to finishedAfter?.let { startedAt.plus(it) },
                        "nodesUpserted" to 3,
                        "edgesUpserted" to 2,
                        "tombstones" to 1,
                        "error" to error,
                    ).filterValues { it != null },
                provenance = Provenance(sourceSystem = "sdlc-knowledge-graph", ingestedAt = startedAt, validFrom = startedAt),
            ),
        )
        return runId
    }

    @Test
    fun `lists runs newest first, a page at a time, with the total`() {
        val first = run("a", base)
        val second = run("b", base.plusSeconds(60))
        val third = run("c", base.plusSeconds(120))

        val firstPage = store.find(SyncRunQuery(connector = connector, size = 2))
        val secondPage = store.find(SyncRunQuery(connector = connector, page = 1, size = 2))

        assertThat(firstPage.items.map { it.id }).containsExactly(third, second)
        assertThat(firstPage.totalElements).isEqualTo(3)
        assertThat(secondPage.items.map { it.id }).containsExactly(first)
        assertThat(secondPage.totalElements).isEqualTo(3)
    }

    @Test
    fun `filters by status and by when a run started`() {
        run("a", base)
        val partial = run("b", base.plusSeconds(60), status = "PARTIAL")
        val later = run("c", base.plusSeconds(120))

        assertThat(store.find(SyncRunQuery(connector = connector, status = "PARTIAL")).items.map { it.id })
            .containsExactly(partial)
        // From is inclusive and before is exclusive, so adjacent windows never count a run twice.
        val window = SyncRunQuery(connector = connector, startedFrom = base.plusSeconds(60), startedBefore = base.plusSeconds(120))
        assertThat(store.find(window).items.map { it.id }).containsExactly(partial)
        assertThat(store.find(SyncRunQuery(connector = connector, startedFrom = base.plusSeconds(120))).items.map { it.id })
            .containsExactly(later)
    }

    @Test
    fun `reads one run back with its own id, its times and its counts`() {
        val id = run("a", base, status = "PARTIAL", error = "page 2 failed")

        val found = store.findById(id)

        assertThat(found).isNotNull
        // The store rewrites the `id` property to the qualified "SyncRun:<id>"; the run id is the key.
        assertThat(found!!.id).isEqualTo(id)
        assertThat(found.connector).isEqualTo(connector)
        assertThat(found.status).isEqualTo("PARTIAL")
        assertThat(found.startedAt).isEqualTo(base)
        assertThat(found.finishedAt).isEqualTo(base.plusSeconds(90))
        assertThat(found.nodesUpserted).isEqualTo(3)
        assertThat(found.edgesUpserted).isEqualTo(2)
        assertThat(found.tombstones).isEqualTo(1)
        assertThat(found.error).isEqualTo("page 2 failed")
        assertThat(store.findById("$connector-missing")).isNull()
    }

    @Test
    fun `prunes finished runs before the cutoff, never a running one, and keeps what they produced`() {
        val old = run("old", longAgo)
        val stuck = run("stuck", longAgo, status = "RUNNING", finishedAfter = null)
        val recent = run("recent", cutoff.plusSeconds(60))
        val produced = NodeKey("Team", "$connector-team")
        val provenance = Provenance(sourceSystem = "fake", ingestedAt = longAgo, validFrom = longAgo, syncRunId = old)
        graphStore.upsertNode(GraphNode(produced, mapOf("name" to "$connector-team"), provenance))
        graphStore.upsertEdge(GraphEdge("PRODUCED", NodeKey("SyncRun", old), produced, provenance = provenance))

        val deleted = store.deleteFinishedBefore(cutoff, batchSize = 10)

        assertThat(deleted).isEqualTo(1)
        assertThat(store.findById(old)).isNull()
        assertThat(store.findById(stuck)).isNotNull
        assertThat(store.findById(recent)).isNotNull
        val team = graphStore.findNode(produced)
        assertThat(team).isNotNull
        assertThat(team!!.provenance.syncRunId).isEqualTo(old)
        assertThat(graphStore.countEdges(produced)).isZero()
    }

    @Test
    fun `prunes a run that produced more nodes than a batch holds`() {
        val old = run("prolific", longAgo)
        val provenance = Provenance(sourceSystem = "fake", ingestedAt = longAgo, validFrom = longAgo, syncRunId = old)
        val produced = (1..5).map { NodeKey("Team", "$connector-team-$it") }
        produced.forEach {
            graphStore.upsertNode(GraphNode(it, mapOf("name" to it.key), provenance))
            graphStore.upsertEdge(GraphEdge("PRODUCED", NodeKey("SyncRun", old), it, provenance = provenance))
        }

        assertThat(store.deleteFinishedBefore(cutoff, batchSize = 2)).isEqualTo(1)
        assertThat(store.findById(old)).isNull()
        assertThat(produced.mapNotNull { graphStore.findNode(it) }).hasSize(5)
    }

    @Test
    fun `deletes no more than one batch per call`() {
        for (i in 1..3) run("old-$i", longAgo.plusSeconds(i.toLong()))

        assertThat(store.deleteFinishedBefore(cutoff, batchSize = 2)).isEqualTo(2)
        assertThat(store.deleteFinishedBefore(cutoff, batchSize = 2)).isEqualTo(1)
        assertThat(store.deleteFinishedBefore(cutoff, batchSize = 2)).isZero()
    }

    @Test
    fun `the last success of each source is the latest finish of its successful runs, of any kind (#93)`() {
        val source = "runs-it-source-" + UUID.randomUUID()

        fun sourced(
            id: String,
            finishedAt: Instant?,
            status: String,
            mode: String = "FULL",
        ) = graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", "$connector-$id"),
                props =
                    mapOf(
                        "id" to "$connector-$id",
                        "connector" to connector,
                        "sourceSystem" to source,
                        "mode" to mode,
                        "status" to status,
                        "startedAt" to base,
                        "finishedAt" to finishedAt,
                    ).filterValues { it != null },
                provenance = Provenance(sourceSystem = "sdlc-knowledge-graph", ingestedAt = base, validFrom = base),
            ),
        )
        sourced("full", base.plusSeconds(60), "SUCCESS")
        sourced("webhook", base.plusSeconds(120), "SUCCESS", mode = "WEBHOOK")
        sourced("partial", base.plusSeconds(600), "PARTIAL")
        sourced("running", null, "RUNNING")

        assertThat(store.lastSuccessBySource()).containsEntry(source, base.plusSeconds(120))
    }

    @Test
    fun `a source with no successful run has no last success (#93)`() {
        val source = "runs-it-failing-" + UUID.randomUUID()
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", "$connector-failed"),
                props =
                    mapOf(
                        "id" to "$connector-failed",
                        "connector" to connector,
                        "sourceSystem" to source,
                        "status" to "FAILED",
                        "startedAt" to base,
                        "finishedAt" to base.plusSeconds(5),
                    ),
                provenance = Provenance(sourceSystem = "sdlc-knowledge-graph", ingestedAt = base, validFrom = base),
            ),
        )

        assertThat(store.lastSuccessBySource()).doesNotContainKey(source)
    }
}
