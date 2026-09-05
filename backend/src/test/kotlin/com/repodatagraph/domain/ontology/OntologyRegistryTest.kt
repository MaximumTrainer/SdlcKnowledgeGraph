package com.repodatagraph.domain.ontology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OntologyRegistryTest {
    private fun nodeType(
        name: String,
        identity: List<String> = listOf("name"),
        properties: List<PropertyDef> = listOf(PropertyDef("name", PropertyType.STRING, required = true)),
    ) = NodeTypeDef(name = name, description = null, identity = identity, properties = properties)

    private fun edgeType(
        name: String,
        from: List<String> = listOf("Repository"),
        to: List<String> = listOf("Team"),
        inverse: String = "INVERSE_OF_$name",
    ) = EdgeTypeDef(name = name, description = null, from = from, to = to, inverse = inverse, properties = emptyList())

    @Test
    fun `types are looked up by name`() {
        val registry =
            OntologyRegistry(
                version = "1.0.0",
                nodeTypes = listOf(nodeType("Repository"), nodeType("Team")),
                edgeTypes = listOf(edgeType("OWNED_BY")),
            )

        assertEquals("Repository", registry.nodeType("Repository")?.name)
        assertEquals("OWNED_BY", registry.edgeType("OWNED_BY")?.name)
        assertEquals(2, registry.allNodeTypes().size)
        assertEquals(1, registry.allEdgeTypes().size)
    }

    @Test
    fun `an unknown name is absent rather than an error`() {
        val registry = OntologyRegistry("1.0.0", listOf(nodeType("Repository")), emptyList())

        assertNull(registry.nodeType("Nonsense"))
        assertNull(registry.edgeType("NONSENSE"))
    }

    @Test
    fun `lookup is case sensitive because labels are`() {
        val registry = OntologyRegistry("1.0.0", listOf(nodeType("Repository")), emptyList())

        assertNull(registry.nodeType("repository"))
    }

    @Test
    fun `an edge without an inverse is rejected, because traversal depends on it`() {
        val error =
            assertThrows<InvalidOntologyException> {
                OntologyRegistry(
                    version = "1.0.0",
                    nodeTypes = listOf(nodeType("Repository"), nodeType("Team")),
                    edgeTypes = listOf(edgeType("OWNED_BY", inverse = " ")),
                )
            }

        assertEquals(true, error.message!!.contains("OWNED_BY"))
    }

    @Test
    fun `an edge pointing at an undeclared node type is rejected`() {
        val error =
            assertThrows<InvalidOntologyException> {
                OntologyRegistry(
                    version = "1.0.0",
                    nodeTypes = listOf(nodeType("Repository")),
                    edgeTypes = listOf(edgeType("OWNED_BY", to = listOf("Team"))),
                )
            }

        assertEquals(true, error.message!!.contains("Team"))
    }

    @Test
    fun `a duplicate node type is rejected rather than silently shadowed`() {
        assertThrows<InvalidOntologyException> {
            OntologyRegistry("1.0.0", listOf(nodeType("Repository"), nodeType("Repository")), emptyList())
        }
    }

    @Test
    fun `identity must reference properties the type actually declares`() {
        val error =
            assertThrows<InvalidOntologyException> {
                OntologyRegistry(
                    version = "1.0.0",
                    nodeTypes = listOf(nodeType("Repository", identity = listOf("missingProp"))),
                    edgeTypes = emptyList(),
                )
            }

        assertEquals(true, error.message!!.contains("missingProp"))
    }

    @Test
    fun `a node type with no identity is rejected, because nodes must be addressable`() {
        assertThrows<InvalidOntologyException> {
            OntologyRegistry("1.0.0", listOf(nodeType("Repository", identity = emptyList())), emptyList())
        }
    }

    @Test
    fun `the version must be semver so it can be compared`() {
        assertThrows<InvalidOntologyException> {
            OntologyRegistry("one", listOf(nodeType("Repository")), emptyList())
        }
    }
}
