package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which repository publishes which package (#86, FR-4), from this run and from the graph.
 */
class PackagePublishersTest {
    private val ledger = NodeKey("Repository", "github.com/acme/ledger")
    private val billing = NodeKey("Repository", "github.com/acme/billing")

    @Test
    fun `finds a publisher whatever the case the name is written in`() {
        val publishers = PackagePublishers.of(listOf("@Acme/Ledger-Client" to ledger))

        assertThat(publishers.publisherOf("@acme/ledger-client")).isEqualTo(ledger)
    }

    @Test
    fun `a name two repositories publish resolves to neither`() {
        // Picking one would be a coin toss recorded as a dependency.
        val publishers = PackagePublishers.of(listOf("ledger-client" to ledger, "ledger-client" to billing))

        assertThat(publishers.publisherOf("ledger-client")).isNull()
    }

    @Test
    fun `what this run read wins over what the graph remembers`() {
        val run = PackagePublishers.of(listOf("ledger-client" to billing))
        val graph = PackagePublishers.of(listOf("ledger-client" to ledger, "billing-client" to billing, "old-client" to ledger))

        val combined = PackagePublishers.combine(run = run, graph = graph, readInRun = setOf(ledger, billing))

        assertThat(combined.publisherOf("ledger-client")).isEqualTo(billing)
        // Ledger was read in this run and no longer publishes old-client; the graph's memory of it is stale.
        assertThat(combined.publisherOf("old-client")).isNull()
    }

    @Test
    fun `the graph answers for a repository this run did not read`() {
        val graph = PackagePublishers.of(listOf("ledger-client" to ledger))

        val combined = PackagePublishers.combine(run = PackagePublishers.NONE, graph = graph, readInRun = setOf(billing))

        assertThat(combined.publisherOf("ledger-client")).isEqualTo(ledger)
    }
}
