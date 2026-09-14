package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Turns what GitHub says into what the ontology declares.
 *
 * The mapper is where a source system's vocabulary stops. Everything downstream - identity,
 * provenance, validity - is the graph's, so the only decisions here are which GitHub field answers
 * which declared property, and what to do about a repository GitHub has archived.
 */
class GitHubRepositoryMapperTest {
    private val mapper = GitHubRepositoryMapper(IdentityResolver())

    private val payments =
        GitHubRepo(
            nodeId = "R_payments",
            name = "Payments",
            fullName = "acme/Payments",
            htmlUrl = "https://github.com/acme/Payments",
            defaultBranch = "main",
            description = "Takes the money",
            language = "Kotlin",
            archived = false,
            visibility = "private",
            pushedAt = Instant.parse("2026-09-01T10:00:00Z"),
            topics = listOf("java", "payments"),
        )

    @Test
    fun `describes the repository with the properties the ontology declares`() {
        val delta = mapper.map(payments, Codeowners.NONE)

        val node = delta.nodes.single { it.type == "Repository" }
        assertThat(node.props)
            .containsEntry("url", "https://github.com/acme/Payments")
            .containsEntry("defaultBranch", "main")
            .containsEntry("topics", listOf("java", "payments"))
            .containsEntry("language", "Kotlin")
            .containsEntry("description", "Takes the money")
    }

    @Test
    fun `says when GitHub saw it, not when we read it`() {
        val node = mapper.map(payments, Codeowners.NONE).nodes.single { it.type == "Repository" }

        // The writer stamps ingestedAt itself. observedAt is the source's own claim about when the
        // fact was true, and losing it makes every ingested fact look as though it happened at once.
        assertThat(node.observedAt).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"))
        assertThat(node.sourceId).isEqualTo("R_payments")
    }

    @Test
    fun `is not guessing`() {
        val node = mapper.map(payments, Codeowners.NONE).nodes.single { it.type == "Repository" }

        assertThat(node.inferred).isFalse()
        assertThat(node.confidence).isEqualTo(1.0)
    }

    @Test
    fun `turns a CODEOWNERS team into a Team and an ownership edge`() {
        val delta = mapper.map(payments, Codeowners(handles = listOf("@acme/platform-team"), teams = listOf("acme/platform-team")))

        val team = delta.nodes.single { it.type == "Team" }
        // Qualified by host as well as org. Two forges, or two orgs, can each have a "platform" team,
        // and an unqualified name would silently make them one node.
        assertThat(team.props).containsEntry("name", "github.com/acme/platform-team")

        val edge = delta.edges.single()
        assertThat(edge.type).isEqualTo("OWNED_BY")
        assertThat(edge.from).isEqualTo(NodeKey("Repository", "github.com/acme/payments"))
        assertThat(edge.to).isEqualTo(NodeKey("Team", "github.com/acme/platform-team"))
    }

    @Test
    fun `records the owners on the repository as GitHub spells them`() {
        val delta =
            mapper.map(
                payments,
                Codeowners(handles = listOf("@some-person", "@acme/platform-team"), teams = listOf("acme/platform-team")),
            )

        val node = delta.nodes.single { it.type == "Repository" }
        assertThat(node.props["codeowners"]).isEqualTo(listOf("@some-person", "@acme/platform-team"))
    }

    @Test
    fun `claims no ownership when CODEOWNERS says nothing`() {
        val delta = mapper.map(payments, Codeowners.NONE)

        assertThat(delta.nodes).noneMatch { it.type == "Team" }
        assertThat(delta.edges).isEmpty()
        // An absent CODEOWNERS is an absence of information, not a statement that nobody owns this.
        assertThat(delta.nodes.single().props["codeowners"]).isEqualTo(emptyList<String>())
    }

    @Test
    fun `closes an archived repository rather than deleting it`() {
        val delta = mapper.map(payments.copy(archived = true), Codeowners.NONE)

        // Still upserted: the node has to exist for its validity to be closed, and "this repository
        // existed until September" is a more useful answer than the repository never appearing.
        assertThat(delta.nodes.single().type).isEqualTo("Repository")
        assertThat(delta.tombstones).containsExactly(NodeKey("Repository", "github.com/acme/payments"))
    }

    @Test
    fun `leaves a live repository alone`() {
        assertThat(mapper.map(payments, Codeowners.NONE).tombstones).isEmpty()
    }

    @Test
    fun `survives the fields GitHub leaves null`() {
        val bare = payments.copy(description = null, language = null, pushedAt = null, topics = emptyList())

        val node = mapper.map(bare, Codeowners.NONE).nodes.single()

        // Absent rather than present-and-null: the registry treats a declared optional property with
        // a null value as a property that was set to nothing, which is a different claim.
        assertThat(node.props).doesNotContainKeys("description", "language")
        assertThat(node.props).containsEntry("topics", emptyList<String>())
        assertThat(node.observedAt).isNull()
    }
}
