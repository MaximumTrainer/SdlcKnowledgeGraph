package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.ontology.Deprecation
import com.repodatagraph.domain.ontology.InvalidOntologyException
import com.repodatagraph.domain.ontology.PropertyFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * What the registry says about a property beyond its type (#81): its examples, format, conditional
 * format and deprecation, and a type's example nodes and questions. A key the loader does not know is
 * a typo that would otherwise be silently ignored, so it fails loading.
 */
class YamlOntologyLoaderSelfDescriptionTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a property's examples, format and deprecation are read`() {
        val registry = load(REGISTRY)
        val repository = registry.nodeType("Repository")!!

        assertEquals(listOf("https://github.com/acme/payments"), repository.property("url")!!.examples)
        assertEquals(PropertyFormat.URL, repository.property("url")!!.format)
        assertEquals(listOf(listOf("billing", "payments")), repository.property("topics")!!.examples)
        assertEquals(listOf(42), repository.property("stars")!!.examples)
        assertEquals(Deprecation(since = "1.3.0", replacedBy = "PROVIDES"), repository.property("serviceId")!!.deprecated)
    }

    @Test
    fun `a conditional format names the property and value it applies to`() {
        val resourceId = load(REGISTRY).nodeType("CloudResource")!!.property("resourceId")!!

        assertEquals(PropertyFormat.ARN, resourceId.format)
        assertEquals(mapOf("provider" to "aws"), resourceId.formatWhen)
    }

    @Test
    fun `a type's example nodes and questions are read, in order`() {
        val repository = load(REGISTRY).nodeType("Repository")!!

        assertEquals(listOf("Which team owns this repository?", "What is built from it?"), repository.questions)
        assertEquals("payments", repository.examples.single()["name"])
        assertEquals(listOf("billing"), repository.examples.single()["topics"])
    }

    @Test
    fun `an edge's property examples are read too`() {
        val rule = load(REGISTRY).edgeType("OWNS_RESOURCE")!!.properties.single()

        assertEquals(listOf("tag", "iac"), rule.examples)
    }

    @Test
    fun `none of it is required of a registry that does not use it`() {
        val team = load(REGISTRY).nodeType("CloudResource")!!

        assertEquals(emptyList<String>(), team.questions)
        assertEquals(emptyList<Any?>(), team.property("provider")!!.examples)
        assertEquals(null, team.property("provider")!!.format)
    }

    @ParameterizedTest(name = "an unknown key {0} fails loading")
    @ValueSource(
        strings = [
            "      - { name: slug, type: string, sensitivity: pii }",
            "      - { name: slug, type: string, deprecated: { since: 1.3.0, reason: gone } }",
        ],
    )
    fun `an unknown property key fails loading, naming it`(line: String) {
        val error = assertThrows<InvalidOntologyException> { load(REGISTRY.replace(PROPERTIES_END, "$line\n$PROPERTIES_END")) }

        assertTrue(error.message!!.contains("Repository"), error.message)
        assertTrue(error.message!!.contains("sensitivity") || error.message!!.contains("reason"), error.message)
    }

    @Test
    fun `an unknown node type key fails loading, naming it`() {
        val error =
            assertThrows<InvalidOntologyException> {
                load(
                    REGISTRY.replace("    identity: [name]\n", "    identity: [name]\n    owner: platform\n"),
                )
            }

        assertTrue(error.message!!.contains("owner"), error.message)
    }

    @Test
    fun `an unknown edge type key fails loading, naming it`() {
        val error =
            assertThrows<InvalidOntologyException> {
                load(REGISTRY, edges = EDGES.replace("    inverse: OWNED_BY_REPO\n", "    inverse: OWNED_BY_REPO\n    weight: 3\n"))
            }

        assertTrue(error.message!!.contains("weight"), error.message)
    }

    @Test
    fun `a format the registry does not know fails loading`() {
        val error = assertThrows<InvalidOntologyException> { load(REGISTRY.replace("format: url", "format: uri")) }

        assertTrue(error.message!!.contains("uri"), error.message)
    }

    private fun load(
        nodes: String,
        edges: String = EDGES,
    ) = YamlOntologyLoader(
        DefaultResourceLoader().apply {
            addProtocolResolver { location, _ ->
                location.removePrefix("classpath:").takeIf { it.startsWith(BASE) }?.let {
                    FileSystemResource(dir.resolve(it.removePrefix("$BASE/")))
                }
            }
        },
    ).also {
        dir.resolve("version.yaml").writeText("version: 1.3.0\n")
        dir.resolve("nodes.yaml").writeText(nodes)
        dir.resolve("edges.yaml").writeText(edges)
    }.load(BASE)

    private companion object {
        const val BASE = "test-described"
        const val PROPERTIES_END = "  CloudResource:"

        val REGISTRY =
            """
            |nodes:
            |  Repository:
            |    identity: [name]
            |    questions:
            |      - Which team owns this repository?
            |      - What is built from it?
            |    examples:
            |      - { name: payments, url: "https://github.com/acme/payments", topics: [billing] }
            |    properties:
            |      - { name: name, type: string, required: true, examples: [payments] }
            |      - { name: url, type: string, required: true, format: url, examples: ["https://github.com/acme/payments"] }
            |      - { name: topics, type: "string[]", examples: [[billing, payments]] }
            |      - { name: stars, type: int, examples: [42] }
            |      - name: serviceId
            |        type: string
            |        deprecated: { since: 1.3.0, replacedBy: PROVIDES }
            |  CloudResource:
            |    identity: [provider, resourceId]
            |    properties:
            |      - { name: provider, type: string, required: true, enum: [aws, azure, gcp] }
            |      - { name: resourceId, type: string, required: true, format: arn, formatWhen: { provider: aws } }
            |
            """.trimMargin()

        val EDGES =
            """
            |edges:
            |  OWNS_RESOURCE:
            |    from: [Repository]
            |    to: [CloudResource]
            |    inverse: OWNED_BY_REPO
            |    properties:
            |      - { name: rule, type: string, enum: [manual, tag, iac], examples: [tag, iac] }
            |
            """.trimMargin()
    }
}
