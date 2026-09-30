package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.application.impact.TraversalFilterBuilder
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.ChangeLineagePort
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * The change lineage Cypher against a real Neo4j (#85): which deployments carry a work item, what a
 * deployment's artifacts contain and implement, which changes a sha names, and which deployments
 * carry them. The edges walked are the ones the registry's lineage names; closed facts are history
 * and are not walked. Plain Cypher, no APOC.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jChangeLineageQueriesIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var queries: ChangeLineagePort

    @Autowired
    private lateinit var traversals: TraversalFilterBuilder

    @Autowired
    private lateinit var identityResolver: IdentityResolver

    /** Unique per test, so nodes other tests wrote to the same database are never reached. */
    private val run = "it-" + UUID.randomUUID().toString().take(8)
    private val at = Instant.parse("2026-09-13T10:00:00Z")
    private val repositoryKey = "github.com/acme/$run"

    private lateinit var fix: NodeKey
    private lateinit var followUp: NodeKey
    private lateinit var retracted: NodeKey
    private lateinit var workItem: NodeKey
    private lateinit var release: NodeKey
    private lateinit var untraced: NodeKey
    private lateinit var production: NodeKey
    private lateinit var staging: NodeKey
    private lateinit var untracedDeployment: NodeKey

    private fun provenance(validTo: Instant? = null) =
        Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at, validTo = validTo)

    private fun node(
        type: String,
        props: Map<String, Any?>,
        validTo: Instant? = null,
    ): NodeKey {
        val key = identityResolver.keyFor(type, props)
        graphStore.upsertNode(GraphNode(key, props, provenance(validTo)))
        return key
    }

    private fun edge(
        type: String,
        from: NodeKey,
        to: NodeKey,
        validTo: Instant? = null,
    ) = graphStore.upsertEdge(GraphEdge(type, from, to, emptyMap(), provenance(validTo)))

    private fun change(sha: String) = node("Change", mapOf("repositoryKey" to repositoryKey, "sha" to sha, "committedAt" to at))

    private fun artifact(version: String) =
        node("Artifact", mapOf("name" to "$run/payments", "version" to version, "artifactType" to "container-image"))

    private fun deploy(
        artifact: NodeKey,
        environment: String,
    ): NodeKey {
        val environmentKey = node("Environment", mapOf("name" to "$run-$environment", "type" to environment))
        val props =
            mapOf(
                "artifactKey" to artifact.key,
                "environmentKey" to environmentKey.key,
                "deployedAt" to at,
                "artifactId" to artifact.key,
                "environmentId" to environmentKey.key,
                "status" to "SUCCESS",
            )
        val deployment = node("Deployment", props)
        edge("DEPLOYED_TO", artifact, deployment)
        edge("TO_ENVIRONMENT", deployment, environmentKey)
        return deployment
    }

    @BeforeEach
    fun graph() {
        fix = change("a1b2c3d4e5")
        followUp = change("f00dfeed")
        retracted = change("deadbeef")
        workItem = node("ExternalWorkItem", mapOf("uri" to "chorus://task/$run", "system" to "chorus"))
        edge("IMPLEMENTS", fix, workItem)
        edge("IMPLEMENTS", followUp, workItem)
        // Closed: a change that no longer claims the work item is history, not lineage.
        edge("IMPLEMENTS", retracted, workItem, validTo = at)

        release = artifact("1.4.0")
        edge("CONTAINS", release, fix)
        edge("CONTAINS", release, followUp)
        edge("CONTAINS", release, retracted)
        production = deploy(release, "production")
        staging = deploy(release, "staging")

        untraced = artifact("1.3.0")
        untracedDeployment = deploy(untraced, "production")
    }

    @Test
    fun `a work item's carriers are every deployment of every artifact containing a change that implements it`() {
        val carriers = queries.carriersOf(workItem, traversals.lineage())

        assertThat(carriers.map { Triple(it.deployment.key, it.artifact.key, it.change.key) })
            .containsExactlyInAnyOrder(
                Triple(production, release, fix),
                Triple(production, release, followUp),
                Triple(staging, release, fix),
                Triple(staging, release, followUp),
            )
        assertThat(carriers.first().deployment.props["status"]).isEqualTo("SUCCESS")
    }

    @Test
    fun `a deployment's contents are its artifacts' changes, each with the work items it implements`() {
        val contents = queries.contentsOf(production, traversals.lineage())

        assertThat(contents.map { it.change.key }).containsExactlyInAnyOrder(fix, followUp, retracted)
        assertThat(contents.single { it.change.key == fix }.workItems.map { it.key }).containsExactly(workItem)
        assertThat(contents.single { it.change.key == retracted }.workItems).isEmpty()
        assertThat(contents.map { it.artifact.key }.toSet()).containsExactly(release)
    }

    @Test
    fun `a deployment whose artifact contains nothing has no contents`() {
        assertThat(queries.contentsOf(untracedDeployment, traversals.lineage())).isEmpty()
    }

    @Test
    fun `a sha names the changes in the repository it begins, or that begin it`() {
        assertThat(queries.changesMatching(NodeKey("Repository", repositoryKey), "a1b2c3")).containsExactly(fix)
        assertThat(queries.changesMatching(NodeKey("Repository", repositoryKey), "f00dfeed0123456789")).containsExactly(followUp)
        assertThat(queries.changesMatching(NodeKey("Repository", repositoryKey), "0badc0de")).isEmpty()
        assertThat(queries.changesMatching(NodeKey("Repository", "github.com/acme/elsewhere-$run"), "a1b2c3")).isEmpty()
    }

    @Test
    fun `the deployments carrying a change are those of the artifacts that contain it`() {
        assertThat(queries.deploymentsCarrying(listOf(fix), traversals.lineage())).containsExactlyInAnyOrder(production, staging)
        assertThat(queries.deploymentsCarrying(emptyList(), traversals.lineage())).isEmpty()
    }

    /**
     * A Change keeps the repository key it was written with, so after the repository is renamed its
     * earlier Changes name a key it no longer has (#88). They are still its Changes: the key is one of
     * the renamed node's previous keys.
     */
    @Test
    fun `a sha names the changes a repository had under a key it has since been renamed from (#88)`() {
        val renamedKey = NodeKey("Repository", "github.com/acme-platform/$run")
        graphStore.upsertNode(
            GraphNode(
                NodeKey("Repository", repositoryKey),
                mapOf(
                    "url" to "https://$repositoryKey",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
                provenance(),
            ),
        )
        graphStore.renameNode(
            NodeKey("Repository", repositoryKey),
            GraphNode(renamedKey, mapOf("url" to "https://${renamedKey.key}"), provenance().copy(previousKeys = listOf(repositoryKey))),
        )

        assertThat(queries.changesMatching(renamedKey, "a1b2c3")).containsExactly(fix)
    }
}
