package com.repodatagraph.application

import com.repodatagraph.domain.exception.InvalidMergeException
import com.repodatagraph.domain.exception.ManagedNodeTypeException
import com.repodatagraph.domain.exception.MergeConflictException
import com.repodatagraph.domain.exception.MergeIntoRetiredException
import com.repodatagraph.domain.exception.NodeAlreadyMergedException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.EdgeMoves
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.identity.MergePlan
import com.repodatagraph.domain.identity.MergeResult
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.ontology.SourceSystemDef
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.NodeMergeStore
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Merging two nodes (#98, FR-4 and FR-5): every refusal is decided before the store is asked to do
 * anything, the plan it is handed is the target's values winning, and the merge is recorded as the
 * principal who asked for it.
 */
class NodeMergeServiceTest {
    private val graphStore: GraphStore = mock()
    private val mergeStore: NodeMergeStore = mock()

    private val registry =
        OntologyRegistry(
            version = "1.5.0",
            nodeTypes =
                listOf(
                    NodeTypeDef(
                        name = "Repository",
                        description = null,
                        identity = listOf("host", "org", "name"),
                        properties =
                            listOf(
                                PropertyDef("url", PropertyType.STRING, required = true),
                                PropertyDef("host", PropertyType.STRING),
                                PropertyDef("org", PropertyType.STRING),
                                PropertyDef("name", PropertyType.STRING),
                                PropertyDef("description", PropertyType.STRING),
                                PropertyDef("language", PropertyType.STRING),
                            ),
                        mergeScope = listOf("host"),
                    ),
                    NodeTypeDef(
                        name = "Artifact",
                        description = null,
                        identity = listOf("registry", "name", "digest"),
                        properties =
                            listOf(
                                PropertyDef("registry", PropertyType.STRING),
                                PropertyDef("name", PropertyType.STRING, required = true),
                                PropertyDef("digest", PropertyType.STRING),
                                PropertyDef("version", PropertyType.STRING, required = true),
                                PropertyDef("commitSha", PropertyType.STRING),
                                PropertyDef("identityQuality", PropertyType.STRING, enum = listOf("digest", "version-only")),
                            ),
                        mergeScope = listOf("registry", "name", "version", "digest"),
                    ),
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true))),
                    NodeTypeDef(
                        "Ontology",
                        null,
                        listOf("version"),
                        listOf(PropertyDef("version", PropertyType.STRING, true)),
                        meta = true,
                    ),
                    NodeTypeDef(
                        "NodeVersion",
                        null,
                        listOf("versionOf", "since"),
                        listOf(PropertyDef("versionOf", PropertyType.STRING, true), PropertyDef("since", PropertyType.INSTANT, true)),
                        meta = true,
                    ),
                ),
            edgeTypes = emptyList(),
            sources = listOf(SourceSystemDef("manual")),
        )

    private val service =
        NodeMergeService(
            registry,
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            graphStore,
            mergeStore,
            StatedProvenance(registry, SourceWriteAuthorization { }) { Principal("dan", PrincipalType.USER) },
        )

    private val earlier = Instant.parse("2026-01-01T00:00:00Z")
    private val from = NodeKey("Repository", "github.com/acme/payments")
    private val into = NodeKey("Repository", "github.com/acme/payments-service")

    private fun repo(
        key: NodeKey,
        extra: Map<String, Any?> = emptyMap(),
        validTo: Instant? = null,
    ): GraphNode {
        val (host, org, name) = key.key.split('/')
        return GraphNode(
            key,
            mapOf("url" to "https://${key.key}", "host" to host, "org" to org, "name" to name) + extra,
            Provenance.manual(earlier).copy(validTo = validTo),
        )
    }

    private fun stubMerge() {
        whenever(mergeStore.merge(any(), any())).thenAnswer { invocation ->
            val plan = invocation.getArgument<MergePlan>(0)
            MergeResult(EdgeMoves(moved = 2, collapsed = 1, dropped = 0), redirected = 0, node = plan.target)
        }
    }

    @Test
    fun `a merge hands the store the target's values winning, and answers what moved`() {
        whenever(graphStore.findNode(from)).thenReturn(repo(from, mapOf("description" to "old", "language" to "Kotlin")))
        whenever(graphStore.findNode(into)).thenReturn(repo(into, mapOf("description" to "new")))
        stubMerge()

        val outcome = service.merge("Repository", from.key, into.key, dryRun = false)

        val plan = argumentCaptor<MergePlan>()
        verify(mergeStore).merge(plan.capture(), eq(false))
        assertThat(plan.firstValue.gained).containsExactly(entry("language", "Kotlin"))
        assertThat(plan.firstValue.kept).containsExactly("description")
        assertThat(plan.firstValue.previousKeys).containsExactly(from.key)
        assertThat(outcome.from).isEqualTo(from)
        assertThat(outcome.into).isEqualTo(into)
        assertThat(outcome.dryRun).isFalse()
        assertThat(outcome.edges).isEqualTo(EdgeMoves(2, 1, 0))
        assertThat(outcome.gained).containsExactly("language")
    }

    @Test
    fun `a merge is recorded as the principal who asked for it (FR-5)`() {
        whenever(graphStore.findNode(from)).thenReturn(repo(from))
        whenever(graphStore.findNode(into)).thenReturn(repo(into))
        stubMerge()

        service.merge("Repository", from.key, "Repository:${into.key}", dryRun = false)

        val plan = argumentCaptor<MergePlan>()
        verify(mergeStore).merge(plan.capture(), any())
        assertThat(plan.firstValue.by.writtenBy).isEqualTo("dan")
        assertThat(plan.firstValue.by.principalType).isEqualTo("user")
    }

    @Test
    fun `a dry run is handed to the store as one`() {
        whenever(graphStore.findNode(from)).thenReturn(repo(from))
        whenever(graphStore.findNode(into)).thenReturn(repo(into))
        stubMerge()

        val outcome = service.merge("Repository", from.key, into.key, dryRun = true)

        verify(mergeStore).merge(any(), eq(true))
        assertThat(outcome.dryRun).isTrue()
    }

    @Test
    fun `a value that would move the target's key is not taken from the source`() {
        // A target known only by host, org and name: the source's url would derive another key.
        val bare = GraphNode(into, mapOf("host" to "github.com", "org" to "acme", "name" to "payments-service"), Provenance.manual(earlier))
        whenever(graphStore.findNode(from)).thenReturn(repo(from, mapOf("language" to "Kotlin")))
        whenever(graphStore.findNode(into)).thenReturn(bare)
        stubMerge()

        service.merge("Repository", from.key, into.key, dryRun = false)

        val plan = argumentCaptor<MergePlan>()
        verify(mergeStore).merge(plan.capture(), any())
        assertThat(plan.firstValue.gained).containsOnlyKeys("language")
    }

    @Test
    fun `a derived property is derived again from the target, not copied from the source`() {
        val versionOnly = NodeKey("Artifact", "payments:1.4.2")
        val digest = NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc")
        whenever(graphStore.findNode(versionOnly)).thenReturn(
            GraphNode(
                versionOnly,
                mapOf("name" to "payments", "version" to "1.4.2", "identityQuality" to "version-only", "commitSha" to "c1"),
                Provenance.manual(earlier),
            ),
        )
        // Written before 1.5.0, so it has no identityQuality of its own.
        whenever(graphStore.findNode(digest)).thenReturn(
            GraphNode(
                digest,
                mapOf("registry" to "ghcr.io/acme", "name" to "payments", "version" to "1.4.2", "digest" to "sha256:abc"),
                Provenance.manual(earlier),
            ),
        )
        stubMerge()

        service.merge("Artifact", versionOnly.key, digest.key, dryRun = false)

        val plan = argumentCaptor<MergePlan>()
        verify(mergeStore).merge(plan.capture(), any())
        assertThat(plan.firstValue.gained).containsEntry("commitSha", "c1").containsEntry("identityQuality", "digest")
    }

    @Test
    fun `a node is not merged into itself`() {
        assertThatThrownBy { service.merge("Repository", from.key, "Repository:${from.key}", dryRun = false) }
            .isInstanceOf(InvalidMergeException::class.java)
            .hasMessageContaining("itself")
        verifyNoInteractions(mergeStore)
    }

    @Test
    fun `a node is not merged into one of another type`() {
        whenever(graphStore.findNode(from)).thenReturn(repo(from))

        assertThatThrownBy { service.merge("Repository", from.key, "Team:billing", dryRun = false) }
            .isInstanceOf(InvalidMergeException::class.java)
            .hasMessageContaining("Team")
        verifyNoInteractions(mergeStore)
    }

    @Test
    fun `the graph's own records are not merged`() {
        assertThatThrownBy { service.merge("Ontology", "1.4.0", "1.5.0", dryRun = false) }
            .isInstanceOf(InvalidMergeException::class.java)
        assertThatThrownBy { service.merge("NodeVersion", "a", "b", dryRun = false) }
            .isInstanceOf(ManagedNodeTypeException::class.java)
        verifyNoInteractions(mergeStore)
    }

    @Test
    fun `a type the registry does not declare is not found`() {
        assertThatThrownBy { service.merge("Widget", "a", "b", dryRun = false) }.isInstanceOf(NodeTypeNotFoundException::class.java)
    }

    @Test
    fun `a node that does not exist is named, whichever side it is on`() {
        whenever(graphStore.findNode(from)).thenReturn(null)
        whenever(graphStore.findNode(into)).thenReturn(null)

        assertThatThrownBy { service.merge("Repository", from.key, into.key, dryRun = false) }
            .isInstanceOf(NodeNotFoundException::class.java)
            .satisfies({ assertThat((it as NodeNotFoundException).missing).containsExactly(from, into) })
    }

    @Test
    fun `a merge into a retired node is refused`() {
        whenever(graphStore.findNode(from)).thenReturn(repo(from))
        whenever(graphStore.findNode(into)).thenReturn(repo(into, validTo = earlier.plusSeconds(60)))

        assertThatThrownBy { service.merge("Repository", from.key, into.key, dryRun = false) }
            .isInstanceOf(MergeIntoRetiredException::class.java)
        verifyNoInteractions(mergeStore)
    }

    @Test
    fun `a node merged already is refused, naming where it went`() {
        val elsewhere = NodeKey("Repository", "github.com/acme/elsewhere")
        whenever(graphStore.findNode(from)).thenReturn(repo(from, validTo = earlier.plusSeconds(60)))
        whenever(graphStore.findNode(into)).thenReturn(repo(into))
        whenever(graphStore.mergedInto(from)).thenReturn(elsewhere)

        assertThatThrownBy { service.merge("Repository", from.key, into.key, dryRun = false) }
            .isInstanceOf(NodeAlreadyMergedException::class.java)
            .satisfies({ assertThat((it as NodeAlreadyMergedException).mergedInto).isEqualTo(elsewhere) })
        verifyNoInteractions(mergeStore)
    }

    @Test
    fun `conflicting identities are refused before the store is asked`() {
        val gitlab = NodeKey("Repository", "gitlab.com/acme/payments")
        whenever(graphStore.findNode(from)).thenReturn(repo(from))
        whenever(graphStore.findNode(gitlab)).thenReturn(repo(gitlab))

        assertThatThrownBy { service.merge("Repository", from.key, gitlab.key, dryRun = false) }
            .isInstanceOf(MergeConflictException::class.java)
            .satisfies({ assertThat((it as MergeConflictException).conflicts.map { c -> c.field }).containsExactly("host") })
        verifyNoInteractions(mergeStore)
    }

    @Test
    fun `an automatic merge is recorded with the provenance of the write that caused it`() {
        whenever(graphStore.findNode(from)).thenReturn(repo(from))
        whenever(graphStore.findNode(into)).thenReturn(repo(into))
        stubMerge()
        val connector = Provenance(sourceSystem = "github-actions", ingestedAt = earlier, validFrom = earlier, syncRunId = "run-1")

        service.mergeAutomatically(from, into, connector)

        val plan = argumentCaptor<MergePlan>()
        verify(mergeStore).merge(plan.capture(), eq(false))
        assertThat(plan.firstValue.by.sourceSystem).isEqualTo("github-actions")
    }
}
