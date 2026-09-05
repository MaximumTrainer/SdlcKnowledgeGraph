package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
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
            )
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
            .andExpect(jsonPath("$.nodeTypes[0].identity").value(listOf("host", "org", "name")))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].name").value("host"))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].type").value("string"))
            .andExpect(jsonPath("$.nodeTypes[0].properties[0].required").value(true))
            .andExpect(jsonPath("$.nodeTypes[0].properties[3].type").value("string[]"))
    }

    @Test
    fun `edge types carry their endpoints and inverse`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.edgeTypes[0].name").value("OWNED_BY"))
            .andExpect(jsonPath("$.edgeTypes[0].inverse").value("OWNS"))
            .andExpect(jsonPath("$.edgeTypes[0].from").value(listOf("Repository")))
            .andExpect(jsonPath("$.edgeTypes[0].to").value(listOf("Team")))
    }

    @Test
    fun `the response can be cached, because the ontology changes only on deploy`() {
        mockMvc
            .perform(get("/api/v1/ontology"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300")))
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

    @Test
    fun `an unknown node type is a 404 naming the problem`() {
        mockMvc
            .perform(get("/api/v1/ontology/nodes/Nonsense"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("unknown node type"))
            .andExpect(jsonPath("$.type").value("Nonsense"))
    }
}
