package com.repodatagraph.domain.ontology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The source systems a write may name (#117): declared in the registry beside the types, validated
 * with them, and the only names a write's provenance may carry.
 */
class OntologyRegistrySourcesTest {
    private fun registry(vararg sources: String) =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes = emptyList(),
            edgeTypes = emptyList(),
            sources = sources.map { SourceSystemDef(it, "the $it source") },
        )

    @Test
    fun `the declared sources are known, in declaration order`() {
        val registry = registry("manual", "github", "aws")

        assertEquals(listOf("manual", "github", "aws"), registry.knownSources())
        assertTrue(registry.isKnownSource("github"))
        assertFalse(registry.isKnownSource("jira"))
    }

    @Test
    fun `a source is matched exactly, not by case or prefix`() {
        val registry = registry("manual", "github")

        assertFalse(registry.isKnownSource("GitHub"))
        assertFalse(registry.isKnownSource("github-enterprise"))
        assertFalse(registry.isKnownSource(""))
    }

    @Test
    fun `a registry that declares no sources knows manual, which a write naming none is`() {
        val registry = OntologyRegistry("1.0.0", emptyList(), emptyList())

        assertEquals(listOf("manual"), registry.knownSources())
    }

    @Test
    fun `a registry declaring sources must declare manual`() {
        val error = assertThrows<InvalidOntologyException> { registry("github", "aws") }

        assertTrue(error.message!!.contains("manual"), error.message)
    }

    @Test
    fun `a source declared twice is rejected`() {
        val error = assertThrows<InvalidOntologyException> { registry("manual", "github", "github") }

        assertTrue(error.message!!.contains("github"), error.message)
    }

    @ParameterizedTest(name = "{0} cannot be a source, because it could not be named in a scope")
    @ValueSource(strings = ["GitHub", "aws:prod", "service now", "graph:write", "-github", "github-", " "])
    fun `a source name must be usable as the last segment of a scope`(name: String) {
        val error = assertThrows<InvalidOntologyException> { registry("manual", name) }

        assertTrue(error.message!!.contains("source"), error.message)
    }
}
