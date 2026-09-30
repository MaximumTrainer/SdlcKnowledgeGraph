package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.EnvironmentDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.ontology.SourceSystemDef
import com.repodatagraph.domain.ontology.TemplateDef
import com.repodatagraph.domain.ontology.TemplateStepDef
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

@WebMvcTest(OntologyController::class)
@Import(OntologyControllerTest.FixedRegistry::class)
class OntologyControllerTest {
    @TestConfiguration
    class FixedRegistry {
        @Bean
        fun ontologyRegistry(): OntologyRegistry =
            OntologyRegistry(
                version = "1.0.0",
                nodeTypes =
                    listOf(
                        NodeTypeDef(
                            name = "Repository",
                            description = "A git repository",
                            identity = listOf("host", "org", "name"),
                            properties =
                                listOf(
                                    PropertyDef("host", PropertyType.STRING, required = true, description = "Host"),
                                    PropertyDef("org", PropertyType.STRING, required = true),
                                    PropertyDef("name", PropertyType.STRING, required = true),
                                    PropertyDef("topics", PropertyType.STRING_ARRAY, required = false),
                                ),
                            displayProperty = "name",
                        ),
                        NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true))),
                    ),
                edgeTypes =
                    listOf(
                        EdgeTypeDef(
                            name = "OWNED_BY",
                            description = null,
                            from = listOf("Repository"),
                            to = listOf("Team"),
                            inverse = "OWNS",
                            properties = emptyList(),
                        ),
                    ),
                sources =
                    listOf(
                        SourceSystemDef("manual", "Stated through the API by whoever made the write"),
                        SourceSystemDef("github", "The GitHub connector"),
                    ),
                environments =
                    listOf(
                        EnvironmentDef("production", "Serves customers", listOf("prod", "live")),
                        EnvironmentDef("staging", "The last stop before production", listOf("stg")),
                    ),
                templates =
                    listOf(
                        TemplateDef(
                            name = "ownership",
                            description = "Who owns a repository",
                            start = listOf("Repository"),
                            owners = false,
                            steps = listOf(TemplateStepDef("OWNED_BY", min = 0, max = 1)),
                        ),
                    ),
            )

        /** A day by default, and six hours for github, so a test can tell an override from the default (#93). */
        @Bean
        fun freshnessPolicy(registry: OntologyRegistry): FreshnessPolicy =
            FreshnessPolicy(Duration.ofHours(24), mapOf("github" to Duration.ofHours(6)), registry.sources.map { it.name })
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `the whole ontology is returned with its version`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.nodeTypes.length()").value(2))
            .andExpect(jsonPath("$.nodeTypes[0].name").value("Repository"))
            .andExpect(jsonPath("$.nodeTypes[0].identity", contains("host", "org", "name")))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].name").value("host"))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].type").value("string"))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].required").value(true))
            .andExpect(jsonPath("$.nodeTypes[0].properties[3].type").value("string[]"))
    }

    @Test
    fun `a node type says which property labels it, and null when it names none (#9)`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nodeTypes[0].displayProperty").value("name"))
            .andExpect(jsonPath("$.nodeTypes[1].displayProperty").value(nullValue()))
    }

    @Test
    fun `edge types carry their endpoints and inverse`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.edgeTypes[0].name").value("OWNED_BY"))
            .andExpect(jsonPath("$.edgeTypes[0].inverse").value("OWNS"))
            .andExpect(jsonPath("$.edgeTypes[0].from", contains("Repository")))
            .andExpect(jsonPath("$.edgeTypes[0].to", contains("Team")))
    }

    @Test
    fun `every source's freshness window is published, as ISO-8601 durations (#93)`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.freshness.defaultWindow").value("PT24H"))
            .andExpect(jsonPath("$.freshness.windows.manual").value("PT24H"))
            .andExpect(jsonPath("$.freshness.windows.github").value("PT6H"))
    }

    @Test
    fun `the source systems a write may name are published, in declaration order (#117)`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sources.length()").value(2))
            .andExpect(jsonPath("$.sources[0].name").value("manual"))
            .andExpect(jsonPath("$.sources[0].description").value("Stated through the API by whoever made the write"))
            .andExpect(jsonPath("$.sources[1].name").value("github"))
    }

    @Test
    fun `the environment names and the aliases folded into each are served, in declaration order (#98)`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.environments.length()").value(2))
            .andExpect(jsonPath("$.environments[0].name").value("production"))
            .andExpect(jsonPath("$.environments[0].description").value("Serves customers"))
            .andExpect(jsonPath("$.environments[0].aliases[0]").value("prod"))
            .andExpect(jsonPath("$.environments[0].aliases[1]").value("live"))
            .andExpect(jsonPath("$.environments[1].name").value("staging"))
    }

    @Test
    fun `the traversal templates of context packs are served, every step spelt out (#96)`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.templates.length()").value(1))
            .andExpect(jsonPath("$.templates[0].name").value("ownership"))
            .andExpect(jsonPath("$.templates[0].description").value("Who owns a repository"))
            .andExpect(jsonPath("$.templates[0].start", contains("Repository")))
            .andExpect(jsonPath("$.templates[0].owners").value(false))
            .andExpect(jsonPath("$.templates[0].steps[0].edge").value("OWNED_BY"))
            .andExpect(jsonPath("$.templates[0].steps[0].where").isMap)
            .andExpect(jsonPath("$.templates[0].steps[0].min").value(0))
            .andExpect(jsonPath("$.templates[0].steps[0].max").value(1))
            .andExpect(jsonPath("$.templates[0].steps[0].current").value(false))
            .andExpect(jsonPath("$.templates[0].steps[0].then").isArray)
    }

    @Test
    fun `the response can be cached, because the ontology changes only on deploy`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", containsString("max-age=300")))
            .andExpect(header().exists("ETag"))
    }

    @Test
    fun `a single node type can be fetched`() {
        mockMvc
            .perform(get("/api/v1/ontology/nodes/Repository"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Repository"))
            .andExpect(jsonPath("$.description").value("A git repository"))
            .andExpect(jsonPath("$.identity.length()").value(3))
    }

    @ParameterizedTest(name = "hostile node type {0} is refused")
    @ValueSource(
        strings = [
            "Repository) DETACH DELETE (n",
            "' OR 1=1 --",
            "<script>alert(1)</script>",
            "../../etc/passwd",
            "Repository ",
        ],
    )
    fun `a hostile type name is refused, never interpolated`(hostileType: String) {
        // The registry is a fixed set of declared names, so untrusted input can only ever miss it.
        mockMvc
            .perform(get("/api/v1/ontology/nodes/{type}", hostileType))
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `an unknown node type is a 404 naming the problem`() {
        mockMvc
            .perform(get("/api/v1/ontology/nodes/Nonsense"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("unknown node type"))
            .andExpect(jsonPath("$.type").value("Nonsense"))
    }
}
