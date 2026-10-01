package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Package name to repository (#86, FR-4): a dependency on something a repository in the graph
 * publishes becomes an edge to that repository, marked as the inference it is; anything else is a
 * library, read straight out of the manifest.
 */
class DependencyResolverTest {
    private val resolver = DependencyResolver(IdentityResolver())
    private val payments = NodeKey("Repository", "github.com/acme/payments")
    private val ledger = NodeKey("Repository", "github.com/acme/ledger")
    private val observed = Instant.parse("2026-09-01T10:00:00Z")

    private fun dependency(
        name: String,
        internal: Boolean = false,
    ) = PendingDependency(
        from = payments,
        source = "acme/payments",
        packageName = name,
        ecosystem = "npm",
        manifest = "package.json",
        version = "^1.0.0",
        scope = "runtime",
        observedAt = observed,
        internal = internal,
    )

    @Test
    fun `a package a repository publishes resolves to that repository, as an inference with its evidence`() {
        val resolution = resolver.resolve(listOf(dependency("ledger-client")), PackagePublishers.of(listOf("ledger-client" to ledger)))

        val edge = resolution.delta.edges.single()
        assertThat(edge.type).isEqualTo("DEPENDS_ON")
        assertThat(edge.from).isEqualTo(payments)
        assertThat(edge.to).isEqualTo(ledger)
        assertThat(edge.props)
            .containsEntry("kind", "library")
            .containsEntry("manifest", "package.json")
            .containsEntry("version", "^1.0.0")
            .containsEntry("scope", "runtime")
        assertThat(edge.inferred).isTrue()
        assertThat(edge.confidence).isLessThan(1.0)
        assertThat(edge.observedAt).isEqualTo(observed)
        assertThat(edge.sourceId).isEqualTo("acme/payments:package.json")
        // Nothing third-party is invented for a package that turned out to be ours.
        assertThat(resolution.delta.nodes).isEmpty()
    }

    @Test
    fun `a package nobody here publishes is a library read from the manifest, at full confidence`() {
        val resolution = resolver.resolve(listOf(dependency("express")), PackagePublishers.NONE)

        val library = resolution.delta.nodes.single()
        assertThat(library.type).isEqualTo("Library")
        assertThat(library.props).containsEntry("ecosystem", "npm").containsEntry("name", "express")
        assertThat(library.sourceId).isEqualTo("npm:express")
        val edge = resolution.delta.edges.single()
        assertThat(edge.to).isEqualTo(NodeKey("Library", "npm:express"))
        assertThat(edge.inferred).isFalse()
        assertThat(edge.confidence).isEqualTo(1.0)
        assertThat(edge.sourceId).isEqualTo("acme/payments:package.json")
    }

    @Test
    fun `a repository does not depend on itself for a package it publishes`() {
        val resolution =
            resolver.resolve(listOf(dependency("@acme/payments-client")), PackagePublishers.of(listOf("@acme/payments-client" to payments)))

        assertThat(resolution.delta.edges).isEmpty()
        assertThat(resolution.delta.nodes).isEmpty()
    }

    @Test
    fun `an internal-looking package no repository publishes can be held back rather than made a library`() {
        val deps = listOf(dependency("@acme/billing", internal = true), dependency("express"))

        val resolution = resolver.resolve(deps, PackagePublishers.NONE, deferUnresolvedInternal = true)

        // A webhook has read one repository, so "nobody publishes it" only means "not read yet".
        assertThat(resolution.deferred).isEqualTo(1)
        assertThat(resolution.delta.nodes.map { it.props["name"] }).containsExactly("express")
    }

    @Test
    fun `a run that has read everything records an unresolved internal-looking package as a library`() {
        val resolution = resolver.resolve(listOf(dependency("@acme/billing", internal = true)), PackagePublishers.NONE)

        assertThat(resolution.deferred).isZero()
        assertThat(
            resolution.delta.nodes
                .single()
                .props,
        ).containsEntry("name", "@acme/billing")
    }
}
