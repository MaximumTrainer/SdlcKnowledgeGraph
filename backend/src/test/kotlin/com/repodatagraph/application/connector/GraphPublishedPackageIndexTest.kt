package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.PublishedPackage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * What the graph already knows each repository publishes (#86, FR-4), so a dependency can resolve to
 * a repository an incremental run did not read, or that another source recorded.
 */
class GraphPublishedPackageIndexTest {
    private val store: GraphStore = mock()
    private val index = GraphPublishedPackageIndex(store)
    private val at = Instant.parse("2026-09-01T10:00:00Z")

    private fun repository(
        name: String,
        packages: List<String>,
        forkOf: String? = null,
        closed: Boolean = false,
    ) = GraphNode(
        NodeKey("Repository", "github.com/acme/$name"),
        buildMap {
            put("packageNames", packages)
            forkOf?.let { put("forkOf", it) }
        },
        Provenance(sourceSystem = "github", ingestedAt = at, validFrom = at, validTo = if (closed) at.plusSeconds(1) else null),
    )

    @Test
    fun `lists every package a current repository publishes, a page at a time`() {
        whenever(store.findNodes(eq("Repository"), eq(emptyMap<String, Any?>()), anyOrNull(), anyOrNull())).thenAnswer { call ->
            when (call.getArgument<String?>(2)) {
                null -> listOf(repository("ledger", listOf("@acme/ledger-client")))
                else -> emptyList<GraphNode>()
            }
        }

        assertThat(index.publishedPackages(includeForks = false))
            .containsExactly(PublishedPackage("@acme/ledger-client", NodeKey("Repository", "github.com/acme/ledger")))
    }

    @Test
    fun `leaves out a fork unless asked, and a repository that has been retired`() {
        whenever(store.findNodes(eq("Repository"), eq(emptyMap<String, Any?>()), anyOrNull(), anyOrNull())).thenAnswer { call ->
            when (call.getArgument<String?>(2)) {
                null ->
                    listOf(
                        repository("ledger-fork", listOf("@acme/ledger-client"), forkOf = "github.com/upstream/ledger"),
                        repository("old", listOf("@acme/old-client"), closed = true),
                    )
                else -> emptyList<GraphNode>()
            }
        }

        assertThat(index.publishedPackages(includeForks = false)).isEmpty()
        assertThat(index.publishedPackages(includeForks = true).map { it.name }).containsExactly("@acme/ledger-client")
    }
}
