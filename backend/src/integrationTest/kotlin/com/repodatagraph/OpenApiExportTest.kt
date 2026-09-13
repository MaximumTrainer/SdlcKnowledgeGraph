package com.repodatagraph

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import java.io.File

/**
 * Keeps `docs/api/openapi.json` equal to the document the application actually serves.
 *
 * The website (#43) renders its API reference from that file, so a reference generated from a stale
 * document is worse than none: it describes endpoints that may not exist. Committing the document and
 * failing here when it drifts is the same arrangement the ontology and the pacts already use.
 *
 * Run with `-DupdateOpenApi=true` to rewrite it after an intentional API change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Neo4jTestcontainersConfig::class)
class OpenApiExportTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val mapper = ObjectMapper()

    @Test
    fun `the committed OpenAPI document is the one the application serves`() {
        val served = normalised(mapper.readTree(restTemplate.getForObject("/api-docs", String::class.java)))
        val rendered = mapper.writer(printer).writeValueAsString(served) + "\n"

        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            committedFile.parentFile.mkdirs()
            committedFile.writeText(rendered)
            return
        }

        assertThat(committedFile)
            .describedAs("%s is missing; run ./gradlew integrationTest -D%s=true", committedFile.path, UPDATE_PROPERTY)
            .exists()
        assertThat(committedFile.readText())
            .describedAs("%s no longer matches /api-docs; run ./gradlew integrationTest -D%s=true", committedFile.path, UPDATE_PROPERTY)
            .isEqualTo(rendered)
    }

    /**
     * Removes what varies between runs. `servers` carries the random port this test was given, so it
     * would make the document differ on every run for no reason a reader cares about.
     */
    private fun normalised(document: JsonNode): JsonNode {
        (document as ObjectNode).remove("servers")
        return sorted(document)
    }

    /** Sorts object keys throughout, so the committed file has one canonical form. */
    private fun sorted(node: JsonNode): JsonNode =
        when (node) {
            is ObjectNode ->
                mapper.createObjectNode().apply {
                    node
                        .properties()
                        .map { it.key }
                        .sorted()
                        .forEach { set<JsonNode>(it, sorted(node.get(it))) }
                }
            is ArrayNode -> mapper.createArrayNode().apply { node.forEach { add(sorted(it)) } }
            else -> node
        }

    private companion object {
        const val UPDATE_PROPERTY = "updateOpenApi"

        /**
         * The default pretty printer breaks lines with `System.lineSeparator()`, so on Windows it
         * renders CRLF while the committed file is LF - `.gitattributes` normalises it deliberately -
         * and the comparison then fails on every line over a difference nobody asked for. Pinning the
         * line break makes the rendered document the same on Windows as in CI.
         *
         * Only the object indenter is replaced. Arrays keep `FixedSpaceIndenter`, which writes no line
         * break, so the committed file's inline arrays stay inline and its bytes do not change.
         */
        val printer: DefaultPrettyPrinter =
            DefaultPrettyPrinter().apply { indentObjectsWith(DefaultIndenter("  ", "\n")) }

        /** Gradle runs from `backend/`, so the repository root is one level up. */
        val committedFile = File("../docs/api/openapi.json")
    }
}
