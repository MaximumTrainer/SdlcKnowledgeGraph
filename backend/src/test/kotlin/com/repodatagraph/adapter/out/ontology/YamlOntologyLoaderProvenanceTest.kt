package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.ontology.PropertyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * The provenance envelope is declared in the registry like everything else (#114, FR-3), so the
 * GraphQL schema, ontology.json and the frontend types pick up a new field from one place.
 */
class YamlOntologyLoaderProvenanceTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()

    @Test
    fun `the envelope declares who wrote a fact and what kind of principal they were`() {
        val byName = registry.provenance.associateBy { it.name }

        assertEquals(PropertyType.STRING, byName["writtenBy"]?.type)
        assertEquals(false, byName["writtenBy"]?.required)
        assertEquals(listOf("user", "service"), byName["principalType"]?.enum)
    }

    @Test
    fun `the envelope declares the team a service principal acted for (#115)`() {
        val onBehalfOfTeam = registry.provenance.single { it.name == "onBehalfOfTeam" }

        assertEquals(PropertyType.STRING, onBehalfOfTeam.type)
        assertEquals(false, onBehalfOfTeam.required)
    }

    @Test
    fun `service principals are a meta type of the registry, keyed by name (#115)`() {
        val type = checkNotNull(registry.nodeType("ServicePrincipal"))

        assertEquals(true, type.meta)
        assertEquals(listOf("name"), type.identity)
        assertEquals(listOf("name", "ownedBy"), type.properties.filter { it.required }.map { it.name })
    }

    @Test
    fun `the envelope keeps the fields it always had, in order`() {
        assertEquals(
            listOf(
                "sourceSystem",
                "sourceId",
                "ingestedAt",
                "observedAt",
                "confidence",
                "inferred",
                "validFrom",
                "validTo",
                "syncRunId",
                "writtenBy",
                "principalType",
                "onBehalfOfTeam",
                // The keys a node had before a rename (#88), appended so none of the others moved.
                "previousKeys",
            ),
            registry.provenance.map { it.name },
        )
    }

    @Test
    fun `confidence is a float`() {
        assertEquals(PropertyType.FLOAT, registry.provenance.single { it.name == "confidence" }.type)
    }
}
