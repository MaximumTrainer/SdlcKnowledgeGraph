package com.repodatagraph.adapter.out.ontology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * `displayProperty` names the property a node is labelled with where it is drawn (#9), so the graph
 * view shows `payments` rather than `github.com/acme/payments` without a table of types in the client.
 */
class YamlOntologyLoaderDisplayTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()

    @Test
    fun `every node type in the shipped registry says which property labels it`() {
        val unlabelled = registry.allNodeTypes().filter { it.displayProperty == null }.map { it.name }

        assertEquals(emptyList<String>(), unlabelled)
    }

    @Test
    fun `each display property is one its type declares`() {
        registry.allNodeTypes().forEach { type ->
            assertTrue(type.property(type.displayProperty.orEmpty()) != null) { "${type.name} displays ${type.displayProperty}" }
        }
    }

    @Test
    fun `a repository and a team are labelled by their name`() {
        assertEquals("name", registry.nodeType("Repository")?.displayProperty)
        assertEquals("name", registry.nodeType("Team")?.displayProperty)
    }
}
