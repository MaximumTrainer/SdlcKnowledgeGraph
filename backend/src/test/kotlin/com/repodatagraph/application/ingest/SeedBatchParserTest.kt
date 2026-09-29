package com.repodatagraph.application.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.application.PropertyValidator
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * What a seed batch may say (#47, FR10): nodes and edges the ontology accepts, of the few types
 * seeding exists for, with every problem named at once so the seed script is fixed in one round.
 */
class SeedBatchParserTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val parser =
        SeedBatchParser(
            objectMapper,
            YamlOntologyLoader(DefaultResourceLoader()).load(),
            PropertyValidator(),
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
        )

    private fun parse(batch: Map<String, Any?>) = parser.parse(objectMapper.writeValueAsBytes(batch))

    private fun errorsOf(batch: Map<String, Any?>) = (parse(batch) as ParsedSeed.Invalid).errors

    private fun repository(url: String) =
        mapOf(
            "type" to "Repository",
            "props" to
                mapOf("url" to url, "defaultBranch" to "main", "topics" to emptyList<String>(), "codeowners" to listOf("@acme/platform")),
            "sourceId" to "https://$url",
        )

    private fun team(name: String) = mapOf("type" to "Team", "props" to mapOf("name" to name))

    private fun pipeline(workflowPath: String) =
        mapOf(
            "type" to "Pipeline",
            "props" to
                mapOf(
                    "provider" to "github-actions",
                    "repoKey" to "github.com/acme/web",
                    "workflowPath" to workflowPath,
                    "name" to workflowPath.substringAfterLast('/'),
                    "repoId" to "github.com/acme/web",
                ),
        )

    private fun edge(
        type: String,
        from: Any?,
        to: Any?,
        props: Map<String, Any?> = emptyMap(),
    ) = mapOf("type" to type, "from" to from, "to" to to, "props" to props)

    private fun batch(
        nodes: List<Any?>,
        edges: List<Any?> = emptyList(),
    ) = mapOf("nodes" to nodes, "edges" to edges)

    @Test
    fun `reads nodes and edges, joining each edge to the keys its ends resolve to`() {
        val parsed =
            parse(
                batch(
                    nodes = listOf(repository("github.com/Acme/Web"), team("Platform"), pipeline(".github/workflows/ci.yml")),
                    edges = listOf(edge("OWNED_BY", 0, 1, mapOf("pathPatterns" to listOf("*"))), edge("HAS_PIPELINE", 0, 2)),
                ),
            ) as ParsedSeed.Valid

        assertThat(parsed.delta.nodes.map { it.type }).containsExactly("Repository", "Team", "Pipeline")
        assertThat(parsed.delta.nodes[0].sourceId).isEqualTo("https://github.com/Acme/Web")
        val owned = parsed.delta.edges[0]
        assertThat(owned.from).isEqualTo(NodeKey("Repository", "github.com/acme/web"))
        assertThat(owned.to).isEqualTo(NodeKey("Team", "platform"))
        assertThat(owned.props).containsEntry("pathPatterns", listOf("*"))
        assertThat(
            parsed.delta.edges[1]
                .to.type,
        ).isEqualTo("Pipeline")
    }

    @Test
    fun `records a dependency on another repository with its kind and manifest`() {
        val parsed =
            parse(
                batch(
                    nodes = listOf(repository("github.com/acme/web"), repository("github.com/acme/ui-kit")),
                    edges = listOf(edge("DEPENDS_ON", 0, 1, mapOf("kind" to "library", "manifest" to "frontend/package.json"))),
                ),
            ) as ParsedSeed.Valid

        assertThat(
            parsed.delta.edges
                .single()
                .to,
        ).isEqualTo(NodeKey("Repository", "github.com/acme/ui-kit"))
        assertThat(
            parsed.delta.edges
                .single()
                .props,
        ).containsEntry("manifest", "frontend/package.json")
    }

    @Test
    fun `refuses node and edge types that seeding is not for`() {
        val errors =
            errorsOf(
                batch(
                    nodes = listOf(mapOf("type" to "Deployment", "props" to mapOf("name" to "x")), team("platform")),
                    edges = listOf(edge("TO_ENVIRONMENT", 1, 1)),
                ),
            )

        assertThat(errors).containsEntry("nodes[0].type", "Deployment cannot be seeded")
        assertThat(errors).containsEntry("edges[0].type", "TO_ENVIRONMENT cannot be seeded")
    }

    @Test
    fun `names every property the ontology refuses, on every node`() {
        val noBranch = repository("github.com/acme/web").let { it + ("props" to (it["props"] as Map<*, *>) - "defaultBranch") }
        val errors = errorsOf(batch(nodes = listOf(noBranch, mapOf("type" to "Team", "props" to mapOf("name" to "x", "colour" to "red")))))

        assertThat(errors).containsEntry("nodes[0].props.defaultBranch", "defaultBranch is required")
        assertThat(errors).containsEntry("nodes[1].props.colour", "not in ontology")
    }

    @Test
    fun `refuses a repository whose url is not a git remote`() {
        val errors = errorsOf(batch(nodes = listOf(repository("not a remote"))))

        assertThat(errors).containsKey("nodes[0].props.url")
    }

    @Test
    fun `refuses an edge whose ends are not nodes in the batch`() {
        val errors = errorsOf(batch(nodes = listOf(team("platform")), edges = listOf(edge("OWNED_BY", 3, "zero"))))

        assertThat(errors).containsEntry("edges[0].from", "edges[0].from must be the index of a node in this batch")
        assertThat(errors).containsEntry("edges[0].to", "edges[0].to must be the index of a node in this batch")
    }

    @Test
    fun `refuses an edge between types the ontology does not connect`() {
        val errors =
            errorsOf(
                batch(nodes = listOf(team("platform"), pipeline(".github/workflows/ci.yml")), edges = listOf(edge("HAS_PIPELINE", 0, 1))),
            )

        assertThat(errors).containsEntry("edges[0]", "HAS_PIPELINE cannot go from Team to Pipeline")
    }

    @Test
    fun `refuses an edge missing a property the ontology requires`() {
        val errors =
            errorsOf(
                batch(
                    nodes = listOf(repository("github.com/acme/web"), repository("github.com/acme/ui-kit")),
                    edges = listOf(edge("DEPENDS_ON", 0, 1)),
                ),
            )

        assertThat(errors).containsEntry("edges[0].props.kind", "kind is required")
    }

    @Test
    fun `refuses a body that is not a batch`() {
        assertThat((parser.parse("[]".toByteArray()) as ParsedSeed.Invalid).errors).containsEntry("body", "body must be a JSON object")
        assertThat(errorsOf(mapOf("nodes" to emptyList<Any>()))).containsEntry("nodes", "nodes must not be empty")
    }

    @Test
    fun `refuses a batch larger than a seed of one repository needs`() {
        val errors = errorsOf(batch(nodes = List(SeedBatchParser.MAX_NODES + 1) { team("team-$it") }))

        assertThat(errors).containsEntry("nodes", "nodes must not number more than ${SeedBatchParser.MAX_NODES}")
    }
}
