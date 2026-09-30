package com.repodatagraph.domain.identity

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a merge of two nodes of one type does to their values (#98, FR-4), before anything touches
 * the graph. The node merged into wins every disagreement; the merged node only fills what the other
 * lacks; and properties the registry says two nodes must share - the merge scope and the alias - are
 * a refusal when both hold them and disagree, not something one side wins.
 */
class MergeRulesTest {
    private val repository =
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
                    PropertyDef("topics", PropertyType.STRING_ARRAY),
                    PropertyDef("provider", PropertyType.STRING),
                    PropertyDef("providerId", PropertyType.STRING),
                ),
            alias = listOf("provider", "providerId"),
            mergeScope = listOf("host"),
        )

    private val at = Instant.parse("2026-01-01T00:00:00Z")

    private fun repo(
        key: String,
        extra: Map<String, Any?> = emptyMap(),
        previousKeys: List<String> = emptyList(),
    ): GraphNode {
        val (host, org, name) = key.split('/')
        return GraphNode(
            NodeKey("Repository", key),
            mapOf("url" to "https://$key", "host" to host, "org" to org, "name" to name) + extra,
            Provenance.manual(at).copy(previousKeys = previousKeys),
        )
    }

    @Test
    fun `nodes that differ only in what the key is built from do not conflict`() {
        assertThat(MergeRules.conflicts(repository, repo("github.com/acme/payments"), repo("github.com/acme/payments-service")))
            .isEmpty()
    }

    @Test
    fun `a property in the merge scope that both hold and disagree on is a conflict, with both values`() {
        val conflicts = MergeRules.conflicts(repository, repo("github.com/acme/payments"), repo("gitlab.com/acme/payments"))

        assertThat(conflicts).containsExactly(MergeConflict("host", "github.com", "gitlab.com"))
    }

    @Test
    fun `two different provider ids are two repositories, whatever the merge scope says`() {
        val source = repo("github.com/acme/payments", mapOf("provider" to "github", "providerId" to "1"))
        val target = repo("github.com/acme/payments-service", mapOf("provider" to "github", "providerId" to "2"))

        assertThat(MergeRules.conflicts(repository, source, target).map { it.field }).containsExactly("providerId")
    }

    @Test
    fun `a property only one side holds is not a conflict`() {
        val source = repo("github.com/acme/payments", mapOf("provider" to "github", "providerId" to "1"))

        assertThat(MergeRules.conflicts(repository, source, repo("github.com/acme/payments-service"))).isEmpty()
    }

    @Test
    fun `the target keeps every value it holds and takes from the source only what it lacks`() {
        val source = repo("github.com/acme/payments", mapOf("description" to "old", "language" to "Kotlin", "topics" to listOf("a")))
        val target = repo("github.com/acme/payments-service", mapOf("description" to "new", "topics" to emptyList<String>()))

        assertThat(MergeRules.fills(repository, source, target)).containsExactly(entry("language", "Kotlin"))
        assertThat(MergeRules.kept(repository, source, target)).containsExactly("description", "topics")
    }

    @Test
    fun `the source's identity is never copied onto the target`() {
        val source = repo("github.com/acme/payments")
        val target = GraphNode(NodeKey("Repository", "github.com/acme/payments-service"), mapOf("url" to "https://x"), source.provenance)

        assertThat(MergeRules.fills(repository, source, target)).doesNotContainKeys("host", "org", "name")
    }

    @Test
    fun `an alias the target lacks moves with the merge, and the source gives it up`() {
        val source = repo("github.com/acme/payments", mapOf("provider" to "github", "providerId" to "1"))
        val target = repo("github.com/acme/payments-service")

        assertThat(MergeRules.fills(repository, source, target)).containsEntry("provider", "github").containsEntry("providerId", "1")
        assertThat(MergeRules.released(repository, source, target)).containsExactly("provider", "providerId")
    }

    @Test
    fun `an alias the target holds already stays where it is`() {
        val source = repo("github.com/acme/payments", mapOf("provider" to "github", "providerId" to "1"))
        val target = repo("github.com/acme/payments-service", mapOf("provider" to "github", "providerId" to "1"))

        assertThat(MergeRules.released(repository, source, target)).isEmpty()
    }

    @Test
    fun `the target's previous keys gain the source's key and every key the source had, each once`() {
        val source = repo("github.com/acme/payments", previousKeys = listOf("github.com/acme/pay", "github.com/acme/old"))
        val target = repo("github.com/acme/payments-service", previousKeys = listOf("github.com/acme/old"))

        assertThat(MergeRules.previousKeys(source, target))
            .containsExactly("github.com/acme/old", "github.com/acme/payments", "github.com/acme/pay")
    }

    @Test
    fun `a key the target holds now is never one of its previous keys`() {
        val source = repo("github.com/acme/payments", previousKeys = listOf("github.com/acme/payments-service"))
        val target = repo("github.com/acme/payments-service")

        assertThat(MergeRules.previousKeys(source, target)).containsExactly("github.com/acme/payments")
    }
}
