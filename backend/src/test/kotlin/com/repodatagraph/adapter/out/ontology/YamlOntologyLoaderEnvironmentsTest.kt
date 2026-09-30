package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.ontology.InvalidOntologyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * environments.yaml (#98, FR-3): the environment alias table, read beside the rest of the registry
 * and validated as it loads, so a table that folds one spelling into two environments stops startup.
 */
class YamlOntologyLoaderEnvironmentsTest {
    @TempDir
    lateinit var dir: File

    private val shipped = YamlOntologyLoader(DefaultResourceLoader()).load()

    @Test
    fun `the shipped table is the one IdentityResolver used to hold, unchanged`() {
        assertEquals(
            mapOf(
                "prod" to "production",
                "prd" to "production",
                "live" to "production",
                "stg" to "staging",
                "stage" to "staging",
                "dev" to "development",
                "test" to "testing",
            ),
            shipped.environmentAliases.asMap(),
        )
    }

    @Test
    fun `every shipped environment says what it is`() {
        shipped.environments.forEach { assertTrue(!it.description.isNullOrBlank(), "${it.name} has no description") }
    }

    @Test
    fun `the environments are read in declaration order, with their aliases`() {
        write(
            "environments.yaml",
            """
            environments:
              - { name: production, description: Serves customers, aliases: [prod, live] }
              - { name: dogfood, description: Our own instance }
            """.trimIndent(),
        )

        val environments = load().environments

        assertEquals(listOf("production", "dogfood"), environments.map { it.name })
        assertEquals(listOf("prod", "live"), environments[0].aliases)
        assertEquals(emptyList<String>(), environments[1].aliases)
    }

    @Test
    fun `a registry without environments yaml folds no names`() {
        assertEquals(emptyMap<String, String>(), load().environmentAliases.asMap())
    }

    @Test
    fun `an alias mapped to two environments stops the load`() {
        write(
            "environments.yaml",
            """
            environments:
              - { name: production, description: Serves customers, aliases: [live] }
              - { name: demo, description: Shown to prospects, aliases: [live] }
            """.trimIndent(),
        )

        val error = assertThrows<InvalidOntologyException> { load() }

        assertTrue(error.message!!.contains("'live'"), error.message)
    }

    @Test
    fun `an environment with no name, or a key the loader does not read, stops the load`() {
        write("environments.yaml", "environments:\n  - { description: nameless }\n")
        assertThrows<InvalidOntologyException> { load() }

        write("environments.yaml", "environments:\n  - { name: production, alias: [prod] }\n")
        val error = assertThrows<InvalidOntologyException> { load() }
        assertTrue(error.message!!.contains("alias"), error.message)
    }

    @Test
    fun `an environments yaml that is not a list stops the load`() {
        write("environments.yaml", "environments:\n  production: [prod]\n")

        assertThrows<InvalidOntologyException> { load() }
    }

    private fun write(
        name: String,
        content: String,
    ) {
        dir.resolve(name).writeText(content)
    }

    /** A registry of one type and no edges, with whatever environments.yaml the test wrote. */
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
        const val BASE = "test-environments"
    }
}
