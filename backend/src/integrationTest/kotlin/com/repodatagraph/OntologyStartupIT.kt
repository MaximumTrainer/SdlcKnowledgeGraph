package com.repodatagraph

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.ontology.InvalidOntologyException
import com.repodatagraph.domain.ontology.OntologyDriftException
import com.repodatagraph.domain.ontology.OntologyDriftValidator
import com.repodatagraph.domain.ontology.OntologyRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.DefaultResourceLoader

/**
 * The registry that actually ships has to load and agree with the Kotlin model. A unit test with a
 * hand-built registry cannot prove that, because the thing most likely to be wrong is the YAML.
 */
class OntologyStartupIT {
    private val loader = YamlOntologyLoader(DefaultResourceLoader())

    @Test
    fun `the shipped ontology loads and declares the minimum viable graph`() {
        val registry = loader.load()

        assertEquals("1.0.0", registry.version)
        val names = registry.allNodeTypes().map { it.name }.toSet()

        // Named rather than counted, and checked for presence rather than equality. An exact set also
        // caught a type being dropped, but it caught every type being *added* just as loudly - so it
        // failed for the wrong reason every time the ontology grew.
        val minimumViableGraph =
            setOf(
                "Repository",
                "Team",
                "Service",
                "Pipeline",
                "Artifact",
                "Deployment",
                "Environment",
                "CloudResource",
                "ConfigurationItem",
            )
        assertTrue(names.containsAll(minimumViableGraph)) { "missing " + (minimumViableGraph - names) }

        // The graph describing itself: which ontology built it, and what has been ingested into it.
        val meta = setOf("Ontology", "SyncRun", "ConnectorState")
        assertTrue(names.containsAll(meta)) { "missing " + (meta - names) }
    }

    @Test
    fun `every shipped edge declares an inverse and connects declared types`() {
        val registry = loader.load()

        assertTrue(registry.allEdgeTypes().isNotEmpty())
        registry.allEdgeTypes().forEach { edge ->
            assertTrue(edge.inverse.isNotBlank(), "${edge.name} has no inverse")
            (edge.from + edge.to).forEach { endpoint ->
                assertTrue(registry.isKnownNodeType(endpoint), "${edge.name} references unknown type $endpoint")
            }
        }
    }

    @Test
    fun `the shipped registry agrees with the Kotlin model`() {
        val registry = loader.load()

        // Team is representative: if the validator and the shipped YAML disagree, this fails.
        OntologyDriftValidator(registry).validate(mapOf("Team" to Team::class))
    }

    @Test
    fun `a registry demanding a property the Kotlin class lacks stops the application`() {
        val drifted = loader.load(DRIFTED_ONTOLOGY)

        val error =
            assertThrows<OntologyDriftException> {
                OntologyDriftValidator(drifted).validate(mapOf("Team" to Team::class))
            }

        assertTrue(error.message!!.contains("Team.slug"), error.message)
    }

    @Test
    fun `a registry with an edge that has no inverse never loads`() {
        val error = assertThrows<InvalidOntologyException> { loader.load(NO_INVERSE_ONTOLOGY) }

        assertTrue(error.message!!.contains("OWNED_BY"), error.message)
    }

    @Test
    fun `a missing ontology file is reported by name`() {
        val error = assertThrows<InvalidOntologyException> { loader.load("ontology/does-not-exist") }

        assertTrue(error.message!!.contains("does-not-exist"), error.message)
    }

    @Test
    fun `the registry is queryable by name`() {
        val registry: OntologyRegistry = loader.load()

        assertEquals(listOf("host", "org", "name"), registry.nodeType("Repository")?.identity)
        assertEquals("BUILDS", registry.inverseOf("BUILT_FROM"))
        assertEquals(null, registry.nodeType("Nonsense"))
    }

    private companion object {
        const val DRIFTED_ONTOLOGY = "ontology/test-drifted"
        const val NO_INVERSE_ONTOLOGY = "ontology/test-no-inverse"
    }
}
