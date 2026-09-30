package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.lifecycle.ArchiveCounts
import com.repodatagraph.domain.lifecycle.ArchiveRecord
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.ArchiveSink
import com.repodatagraph.domain.port.out.ArchiveStore
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
 * What the archive selects, writes and deletes (#33, FR6), against a real Neo4j. The facts here are
 * dated in the last century so no other test's writes are old enough to be in scope.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jArchiveStoreIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var archiveStore: ArchiveStore

    private val suffix = UUID.randomUUID().toString()
    private val began = Instant.parse("1990-01-01T00:00:00Z")
    private val longAgo = Instant.parse("1995-01-01T00:00:00Z")
    private val cutoff = Instant.parse("1999-01-01T00:00:00Z")
    private val recently = Instant.parse("1999-06-01T00:00:00Z")

    private fun valid(to: Instant?) = Provenance(sourceSystem = "manual", ingestedAt = began, validFrom = began, validTo = to)

    private fun resource(
        name: String,
        to: Instant?,
    ): NodeKey {
        val id = "arn:aws:s3:::archive-$name-$suffix"
        val key = NodeKey("CloudResource", "aws:$id")
        graphStore.upsertNode(GraphNode(key, mapOf("provider" to "aws", "resourceId" to id, "name" to key.key), valid(to)))
        return key
    }

    private class Collecting : ArchiveSink {
        val records = mutableListOf<ArchiveRecord>()
        override val location = "memory"

        override fun write(record: ArchiveRecord) {
            records += record
        }

        override fun close() = Unit
    }

    @Test
    fun `selects closed facts past the cutoff, writes them, and purges only them`() {
        val old = resource("old", longAgo)
        val recent = resource("recent", recently)
        val open = resource("open", null)
        val owner = NodeKey("Team", "archive-owner-$suffix")
        graphStore.upsertNode(GraphNode(owner, mapOf("name" to owner.key), valid(null)))
        graphStore.upsertEdge(GraphEdge("OWNED_BY", old, owner, emptyMap(), valid(null)))
        graphStore.upsertEdge(GraphEdge("OWNED_BY", recent, owner, emptyMap(), valid(longAgo)))

        assertThat(archiveStore.count(cutoff)).isEqualTo(ArchiveCounts(nodes = 1, edges = 2))

        val sink = Collecting()
        assertThat(archiveStore.export(cutoff, sink)).isEqualTo(ArchiveCounts(nodes = 1, edges = 2))
        assertThat(sink.records.filter { it.kind == "node" }.map { it.data["id"] }).containsExactly(old.id)
        assertThat(sink.records.filter { it.kind == "edge" }.map { it.data["from"] }).containsExactlyInAnyOrder(old.id, recent.id)
        val exported = sink.records.first { it.kind == "node" }.data
        assertThat((exported["props"] as Map<*, *>)["name"]).isEqualTo(old.key)
        assertThat((exported["provenance"] as Map<*, *>).keys).contains("validTo")

        assertThat(archiveStore.purge(cutoff)).isEqualTo(ArchiveCounts(nodes = 1, edges = 2))

        assertThat(graphStore.findNode(old)).isNull()
        assertThat(graphStore.findNode(recent)).isNotNull
        assertThat(graphStore.findNode(open)).isNotNull
        assertThat(graphStore.findEdge("OWNED_BY", recent, owner)).describedAs("its edge closed long ago goes").isNull()
        assertThat(archiveStore.count(cutoff)).isEqualTo(ArchiveCounts(0, 0))
    }
}
