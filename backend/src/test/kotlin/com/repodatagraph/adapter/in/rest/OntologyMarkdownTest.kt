package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.ontology.Deprecation
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyFormat
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * The ontology as prompt text (#81): what `GET /api/v1/ontology?format=markdown` serves, and what an
 * agent's `describe_ontology` tool will hand on (#31). It must be the same bytes on every call, so a
 * cached prompt stays cached, and small enough to leave room for the question.
 */
class OntologyMarkdownTest {
    @Test
    fun `a registry renders exactly as the golden file says`() {
        val golden = checkNotNull(javaClass.getResource("/ontology/ontology-markdown.golden.md")).readText()

        assertThat(OntologyMarkdown.render(REGISTRY)).isEqualTo(golden)
    }

    @Test
    fun `rendering twice gives the same bytes`() {
        assertThat(OntologyMarkdown.render(shipped)).isEqualTo(OntologyMarkdown.render(shipped))
    }

    @Test
    fun `the shipped registry renders under 12,000 characters`() {
        assertThat(OntologyMarkdown.render(shipped).length).isLessThan(12_000)
    }

    @Test
    fun `every software type of the shipped registry is there, with its questions`() {
        val markdown = OntologyMarkdown.render(shipped)

        shipped.allNodeTypes().filterNot { it.meta }.forEach { type ->
            val section = markdown.substringAfter("\n## ${type.name}\n", "").substringBefore("\n## ")
            assertThat(section).describedAs(type.name).isNotEmpty()
            type.questions.forEach { assertThat(section).describedAs(type.name).contains(it) }
        }
        assertThat(markdown.substringAfter("\n## Repository\n")).contains("Which team owns this repository?")
    }

    @Test
    fun `meta types and deprecated properties are left out`() {
        val markdown = OntologyMarkdown.render(shipped)

        shipped.allNodeTypes().filter { it.meta }.forEach { assertThat(markdown).doesNotContain("## ${it.name}\n") }
        assertThat(markdown).doesNotContain("- serviceId:")
        assertThat(markdown).doesNotContain("PRODUCED")
    }

    private val shipped = YamlOntologyLoader(DefaultResourceLoader()).load()

    private companion object {
        val REGISTRY =
            OntologyRegistry(
                version = "1.3.0",
                nodeTypes =
                    listOf(
                        NodeTypeDef(
                            name = "Repository",
                            description = "A git repository, the anchor for most of the graph.",
                            identity = listOf("name"),
                            properties =
                                listOf(
                                    PropertyDef("name", PropertyType.STRING, required = true, examples = listOf("payments")),
                                    PropertyDef(
                                        "url",
                                        PropertyType.STRING,
                                        required = true,
                                        format = PropertyFormat.URL,
                                        examples = listOf("https://github.com/acme/payments"),
                                    ),
                                    PropertyDef("topics", PropertyType.STRING_ARRAY, examples = listOf(listOf("billing"))),
                                    PropertyDef(
                                        "serviceId",
                                        PropertyType.STRING,
                                        examples = listOf("payments-api"),
                                        deprecated = Deprecation("1.3.0", "PROVIDES"),
                                    ),
                                ),
                            questions = listOf("Which team owns this repository?"),
                        ),
                        NodeTypeDef(
                            name = "CloudResource",
                            description = "An infrastructure object in AWS, Azure or GCP.",
                            identity = listOf("provider", "resourceId"),
                            properties =
                                listOf(
                                    PropertyDef(
                                        "provider",
                                        PropertyType.STRING,
                                        required = true,
                                        enum = listOf("aws", "azure", "gcp"),
                                        examples = listOf("aws"),
                                    ),
                                    PropertyDef(
                                        "resourceId",
                                        PropertyType.STRING,
                                        required = true,
                                        format = PropertyFormat.ARN,
                                        formatWhen = mapOf("provider" to "aws"),
                                        examples = listOf("arn:aws:s3:::acme-logs"),
                                    ),
                                ),
                        ),
                        NodeTypeDef(
                            name = "SyncRun",
                            description = "One execution of a connector.",
                            identity = listOf("id"),
                            properties = listOf(PropertyDef("id", PropertyType.STRING, required = true, examples = listOf("run-1"))),
                            meta = true,
                        ),
                    ),
                edgeTypes =
                    listOf(
                        EdgeTypeDef(
                            name = "OWNS_RESOURCE",
                            description = "A repository is responsible for a piece of infrastructure.",
                            from = listOf("Repository"),
                            to = listOf("CloudResource"),
                            inverse = "OWNED_BY_REPO",
                            properties =
                                listOf(
                                    PropertyDef(
                                        "rule",
                                        PropertyType.STRING,
                                        enum = listOf("manual", "tag", "iac"),
                                        examples = listOf("tag"),
                                    ),
                                ),
                        ),
                        EdgeTypeDef(
                            name = "PRODUCED",
                            description = "A sync run asserted this node.",
                            from = listOf("SyncRun"),
                            to = listOf("Repository", "CloudResource"),
                            inverse = "PRODUCED_BY",
                        ),
                    ),
            )
    }
}
