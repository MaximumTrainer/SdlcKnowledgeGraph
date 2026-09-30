package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.ontology.InvalidOntologyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * sources.yaml (#117): the source systems a write may name, read beside the rest of the registry.
 */
class YamlOntologyLoaderSourcesTest {
    @TempDir
    lateinit var dir: File

    private val shipped = YamlOntologyLoader(DefaultResourceLoader()).load()

    @Test
    fun `the shipped registry declares at least the sources #117 names, manual first`() {
        val names = shipped.knownSources()

        assertEquals("manual", names.first())
        assertTrue(names.containsAll(listOf("manual", "github", "github-actions", "aws", "servicenow")), names.toString())
    }

    @Test
    fun `the shipped registry declares every source this application stamps on what it writes`() {
        // The ingest endpoints' connectors and the sync run records name these; the connectors the
        // application runs are checked against the registry in SourceSystemsIT.
        listOf("dogfood-seed", "github-actions", "sdlc-knowledge-graph")
            .forEach { assertTrue(shipped.isKnownSource(it), "$it is not declared in ${shipped.knownSources()}") }
    }

    @Test
    fun `every shipped source says what it is`() {
        shipped.sources.forEach { assertTrue(!it.description.isNullOrBlank(), "${it.name} has no description") }
    }

    @Test
    fun `a source's description is optional`() {
        write("sources.yaml", "sources:\n  - { name: manual }\n  - { name: github, description: The GitHub connector }\n")

        val sources = load().sources

        assertEquals(listOf("manual", "github"), sources.map { it.name })
        assertNull(sources[0].description)
        assertEquals("The GitHub connector", sources[1].description)
    }

    @Test
    fun `a registry without sources yaml knows only manual`() {
        assertEquals(listOf("manual"), load().knownSources())
    }

    @Test
    fun `a source with no name is rejected`() {
        write("sources.yaml", "sources:\n  - { name: manual }\n  - { description: nameless }\n")

        val error = assertThrows<InvalidOntologyException> { load() }

        assertTrue(error.message!!.contains("source"), error.message)
    }

    @Test
    fun `a sources yaml that is not a list is rejected`() {
        write("sources.yaml", "sources:\n  manual: {}\n")

        assertThrows<InvalidOntologyException> { load() }
    }

    private fun write(
        name: String,
        content: String,
    ) {
        dir.resolve(name).writeText(content)
    }

    /** A registry of one type and no edges, with whatever sources.yaml the test wrote. */
    private fun load() =
        YamlOntologyLoader(
            DefaultResourceLoader().apply {
                addProtocolResolver { location, _ ->
                    location.removePrefix("classpath:").takeIf { it.startsWith(BASE) }?.let {
                        FileSystemResource(dir.resolve(it.removePrefix("$BASE/")))
                    }
                }
            },
        ).also {
            write("version.yaml", "version: 1.0.0\n")
            write(
                "nodes.yaml",
                "nodes:\n  Team:\n    identity: [name]\n    properties:\n      - { name: name, type: string, required: true }\n",
            )
            write("edges.yaml", "edges: {}\n")
        }.load(BASE)

    private companion object {
        const val BASE = "test-sources"
    }
}
