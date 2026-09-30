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
 * What the registry says about identity in a real estate (#98): which properties two nodes must not
 * disagree on to be merged (`mergeScope`), how sure an Artifact's key is, the directory a monorepo
 * provides a service from, and that a node can be retired because it was merged.
 */
class YamlOntologyLoaderMergeScopeTest {
    @TempDir
    lateinit var dir: File

    private val shipped = YamlOntologyLoader(DefaultResourceLoader()).load()

    @Test
    fun `two repositories on different hosts, or two artifacts of different versions, are never one`() {
        assertEquals(listOf("host"), shipped.nodeType("Repository")!!.mergeScope)
        assertEquals(listOf("registry", "name", "version", "digest"), shipped.nodeType("Artifact")!!.mergeScope)
    }

    @Test
    fun `an artifact says how sure its key is`() {
        val quality = shipped.nodeType("Artifact")!!.properties.single { it.name == "identityQuality" }

        assertEquals(listOf("digest", "version-only"), quality.enum)
        assertTrue(!quality.required)
    }

    @Test
    fun `PROVIDES names the directory the service is provided from`() {
        val path = shipped.edgeType("PROVIDES")!!.properties.single { it.name == "path" }

        assertTrue(!path.required)
        assertTrue(path.examples.isNotEmpty())
    }

    @Test
    fun `a version can record that its node was retired by a merge`() {
        val reason = shipped.nodeType("NodeVersion")!!.properties.single { it.name == "retiredReason" }

        assertTrue("merged" in reason.enum.orEmpty(), reason.enum.toString())
    }

    @Test
    fun `a type names no merge scope unless it says so`() {
        assertEquals(emptyList<String>(), load("").nodeType("Team")!!.mergeScope)
    }

    @Test
    fun `a merge scope is read in the order it is written`() {
        assertEquals(listOf("email", "name"), load("    mergeScope: [email, name]\n").nodeType("Team")!!.mergeScope)
    }

    @Test
    fun `a merge scope naming a property the type does not declare stops the load`() {
        val error = assertThrows<InvalidOntologyException> { load("    mergeScope: [colour]\n") }

        assertTrue(error.message!!.contains("colour"), error.message)
    }

    private fun load(mergeScope: String) =
        YamlOntologyLoader(
            DefaultResourceLoader().apply {
                addProtocolResolver { location, _ ->
                    location.removePrefix("classpath:").takeIf { it.startsWith(BASE) }?.let {
                        FileSystemResource(dir.resolve(it.removePrefix("$BASE/")))
                    }
                }
            },
        ).also {
            dir.resolve("version.yaml").writeText("version: 1.0.0\n")
            dir.resolve("nodes.yaml").writeText(
                "nodes:\n  Team:\n    identity: [name]\n$mergeScope    properties:\n" +
                    "      - { name: name, type: string, required: true }\n      - { name: email, type: string }\n",
            )
            dir.resolve("edges.yaml").writeText("edges: {}\n")
        }.load(BASE)

    private companion object {
        const val BASE = "test-merge-scope"
    }
}
