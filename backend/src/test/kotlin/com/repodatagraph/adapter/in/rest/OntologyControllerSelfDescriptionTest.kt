package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.ontology.Deprecation
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyFormat
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/**
 * What `GET /api/v1/ontology` tells a machine reader about each type and property (#81), and the
 * Markdown rendering of the same registry that an agent is handed as prompt text.
 */
@WebMvcTest(OntologyController::class)
@Import(OntologyControllerSelfDescriptionTest.DescribedRegistry::class)
class OntologyControllerSelfDescriptionTest {
    @TestConfiguration
    class DescribedRegistry {
        @Bean
        fun ontologyRegistry(): OntologyRegistry =
            OntologyRegistry(
                version = "1.3.0",
                nodeTypes = listOf(REPOSITORY, CLOUD_RESOURCE),
                edgeTypes = listOf(OWNS_RESOURCE),
            )

        /** The windows are configuration, not registry (#93); the Markdown leaves them out. */
        @Bean
        fun freshnessPolicy(registry: OntologyRegistry): FreshnessPolicy =
            FreshnessPolicy(Duration.ofHours(24), emptyMap(), registry.sources.map { it.name })

        private companion object {
            val REPOSITORY =
                NodeTypeDef(
                    name = "Repository",
                    description = "A git repository, the anchor for most of the graph.",
                    identity = listOf("name"),
                    properties =
                        listOf(
                            PropertyDef(
                                "name",
                                PropertyType.STRING,
                                required = true,
                                description = "The repository's name within its organisation",
                                examples = listOf("payments"),
                            ),
                            PropertyDef(
                                "url",
                                PropertyType.STRING,
                                required = true,
                                description = "Where the repository is served, canonicalised",
                                format = PropertyFormat.URL,
                                examples = listOf("https://github.com/acme/payments"),
                            ),
                            PropertyDef(
                                "serviceId",
                                PropertyType.STRING,
                                description = "The service it provides, from before PROVIDES",
                                examples = listOf("payments-api"),
                                deprecated = Deprecation(since = "1.3.0", replacedBy = "PROVIDES"),
                            ),
                        ),
                    displayProperty = "name",
                    examples = listOf(mapOf("name" to "payments", "url" to "https://github.com/acme/payments")),
                    questions = listOf("Which team owns this repository?"),
                )

            val CLOUD_RESOURCE =
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
                                description = "The cloud the resource lives in",
                                enum = listOf("aws", "azure", "gcp"),
                                examples = listOf("aws"),
                            ),
                            PropertyDef(
                                "resourceId",
                                PropertyType.STRING,
                                required = true,
                                description = "The cloud's own identifier for the resource",
                                format = PropertyFormat.ARN,
                                formatWhen = mapOf("provider" to "aws"),
                                examples = listOf("arn:aws:s3:::acme-logs"),
                            ),
                        ),
                    examples = listOf(mapOf("provider" to "aws", "resourceId" to "arn:aws:s3:::acme-logs")),
                )

            val OWNS_RESOURCE =
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
                                description = "Which link rule proposed the ownership",
                                enum = listOf("manual", "tag", "iac"),
                                examples = listOf("tag"),
                            ),
                        ),
                )
        }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `every property carries its examples, and a format or a deprecation where it has one`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].examples", contains("payments")))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].format").doesNotExist())
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].deprecated").doesNotExist())
            .andExpect(jsonPath("$.nodeTypes[0].properties[1].format").value("url"))
            .andExpect(jsonPath("$.nodeTypes[0].properties[2].deprecated.since").value("1.3.0"))
            .andExpect(jsonPath("$.nodeTypes[0].properties[2].deprecated.replacedBy").value("PROVIDES"))
            .andExpect(jsonPath("$.nodeTypes[1].properties[1].format").value("arn"))
            .andExpect(jsonPath("$.nodeTypes[1].properties[1].formatWhen.provider").value("aws"))
            .andExpect(jsonPath("$.edgeTypes[0].properties[0].enum", contains("manual", "tag", "iac")))
            .andExpect(jsonPath("$.edgeTypes[0].properties[0].examples", contains("tag")))
    }

    @Test
    fun `a node type carries an example node and the questions it helps answer`() {
        mockMvc
            .perform(get("/api/v1/ontology/nodes/Repository"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.questions", contains("Which team owns this repository?")))
            .andExpect(jsonPath("$.examples[0].name").value("payments"))
            .andExpect(jsonPath("$.examples[0].url").value("https://github.com/acme/payments"))
    }

    @Test
    fun `the ontology is served as Markdown on request, the same bytes every time`() {
        val first = markdown()
        val second = markdown()

        assertThat(first).isEqualTo(second)
        assertThat(first).contains("## Repository")
        assertThat(first).contains("Which team owns this repository?")
        assertThat(first).contains("OWNS_RESOURCE")
    }

    @Test
    fun `the Markdown is typed as Markdown and cacheable like the JSON`() {
        mockMvc
            .perform(get("/api/v1/ontology").param("format", "markdown"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/markdown"))
            .andExpect(header().string("Cache-Control", containsString("max-age=300")))
            .andExpect(header().exists("ETag"))
    }

    @Test
    fun `json is the default, and may be asked for by name`() {
        mockMvc
            .perform(get("/api/v1/ontology").param("format", "json"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.version").value("1.3.0"))
    }

    @Test
    fun `a format it cannot render is refused, naming the ones it can`() {
        mockMvc
            .perform(get("/api/v1/ontology").param("format", "xml"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown format"))
            .andExpect(jsonPath("$.format").value("xml"))
            .andExpect(jsonPath("$.supported", contains("json", "markdown")))
    }

    private fun markdown(): String =
        mockMvc
            .perform(get("/api/v1/ontology").param("format", "markdown"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
}
