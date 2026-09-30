package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.ontology.InvalidOntologyException
import com.repodatagraph.domain.ontology.TemplateStepDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * templates.yaml (#96, FR-1): the traversal templates of context packs, read beside the edges they
 * walk and validated against them as the registry is built.
 */
class YamlOntologyLoaderTemplatesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `templates are read in declaration order, with every step and its defaults`() {
        write(
            "templates.yaml",
            """
            templates:
              upstream:
                description: What a repository depends on
                start: [Repository]
                steps:
                  - edge: DEPENDS_ON
                    where: { kind: api }
                    min: 0
                    max: 2
                    then:
                      - { edge: OWNED_BY }
              owners:
                description: Who owns a repository
                start: [Repository]
                owners: true
                steps:
                  - { edge: OWNED_BY }
            """.trimIndent(),
        )

        val templates = load().templates

        assertEquals(listOf("upstream", "owners"), templates.map { it.name })
        val upstream = templates.first()
        assertEquals("What a repository depends on", upstream.description)
        assertEquals(listOf("Repository"), upstream.start)
        assertFalse(upstream.owners)
        assertEquals(
            listOf(TemplateStepDef("DEPENDS_ON", mapOf("kind" to "api"), min = 0, max = 2, then = listOf(TemplateStepDef("OWNED_BY")))),
            upstream.steps,
        )
        assertTrue(templates.last().owners)
    }

    @Test
    fun `a registry without templates yaml has no templates`() {
        assertEquals(emptyList<Any>(), load().templates)
    }

    @Test
    fun `a key the loader does not read stops the load, naming it`() {
        write(
            "templates.yaml",
            "templates:\n  up:\n    description: d\n    start: [Repository]\n    budget: 5\n    steps: [{ edge: DEPENDS_ON }]\n",
        )
        assertTrue(assertThrows<InvalidOntologyException> { load() }.message!!.contains("budget"))

        write(
            "templates.yaml",
            "templates:\n  up:\n    description: d\n    start: [Repository]\n    steps: [{ edge: DEPENDS_ON, depth: 3 }]\n",
        )
        assertTrue(assertThrows<InvalidOntologyException> { load() }.message!!.contains("depth"))
    }

    @Test
    fun `a step with no edge, or a templates yaml that is not a mapping, stops the load`() {
        write("templates.yaml", "templates:\n  up:\n    description: d\n    start: [Repository]\n    steps: [{ min: 0 }]\n")
        assertThrows<InvalidOntologyException> { load() }

        write("templates.yaml", "templates:\n  - up\n")
        assertThrows<InvalidOntologyException> { load() }
    }

    @Test
    fun `a template the edges cannot walk stops the load`() {
        write("templates.yaml", "templates:\n  up:\n    description: d\n    start: [Team]\n    steps: [{ edge: DEPENDS_ON }]\n")

        val error = assertThrows<InvalidOntologyException> { load() }

        assertTrue(error.message!!.contains("DEPENDS_ON") && error.message!!.contains("Team"), error.message)
    }

    private fun write(
        name: String,
        content: String,
    ) {
        dir.resolve(name).writeText(content)
    }

    /** Two types and two edges, with whatever templates.yaml the test wrote. */
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
                """
                nodes:
                  Repository:
                    identity: [name]
                    properties:
                      - { name: name, type: string, required: true }
                  Team:
                    identity: [name]
                    properties:
                      - { name: name, type: string, required: true }
                """.trimIndent(),
            )
            write(
                "edges.yaml",
                """
                edges:
                  DEPENDS_ON:
                    from: [Repository]
                    to: [Repository]
                    inverse: DEPENDED_ON_BY
                    properties:
                      - { name: kind, type: string, required: true, enum: [library, api] }
                  OWNED_BY:
                    from: [Repository]
                    to: [Team]
                    inverse: OWNS
                    ownership: owner
                """.trimIndent(),
            )
        }.load(BASE)

    private companion object {
        const val BASE = "test-templates"
    }
}
