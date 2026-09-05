package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Proves the generated files describe the ontology the API actually serves.
 *
 * The unit tests in `buildSrc` cover how the generator renders a given registry; these scenarios
 * cover the thing a consumer depends on, which is that the committed output and the running
 * endpoint have not drifted apart. Reading the files from disk is deliberate: the point is to check
 * what is committed, not what could be regenerated.
 */
class OntologyCodegenSteps(
    private val world: ApiWorld,
    private val objectMapper: ObjectMapper,
) {
    @Then("the response body equals the committed ontology snapshot")
    fun theResponseBodyEqualsTheCommittedSnapshot() {
        val snapshot = objectMapper.readTree(read(SNAPSHOT))

        assertEquals(
            snapshot,
            world.lastBody(),
            "GET /api/v1/ontology and $SNAPSHOT disagree. Run ./gradlew generateOntology and commit the result.",
        )
    }

    @Then("every served node type has a generated GraphQL type")
    fun everyServedNodeTypeHasAGeneratedGraphqlType() {
        val sdl = read(SDL)

        servedNodeTypes().forEach { name ->
            assertTrue(sdl.contains("type ${name}Node implements GraphNode {"), "$SDL declares no type for $name")
        }
    }

    @Then("every served edge type is listed in the generated EdgeType enum")
    fun everyServedEdgeTypeIsListedInTheEnum() {
        val enum = read(SDL).substringAfter("enum EdgeType {").substringBefore("}")

        servedEdgeTypes().forEach { name ->
            assertTrue(enum.lines().any { it.trim() == name }, "$SDL does not list $name in enum EdgeType")
        }
    }

    @Then("every served node type has a generated TypeScript interface")
    fun everyServedNodeTypeHasAGeneratedTypescriptInterface() {
        val typescript = read(TYPESCRIPT)

        servedNodeTypes().forEach { name ->
            assertTrue(typescript.contains("export interface $name {"), "$TYPESCRIPT declares no interface for $name")
            assertTrue(typescript.contains("  '$name'"), "$TYPESCRIPT does not list $name in NODE_TYPES")
        }
    }

    @Then("the generated TypeScript declares the version the endpoint reports")
    fun theGeneratedTypescriptDeclaresTheServedVersion() {
        val served = world.lastBody().path("version").asText()

        assertTrue(
            read(TYPESCRIPT).contains("export const ONTOLOGY_VERSION = '$served'"),
            "$TYPESCRIPT does not declare ONTOLOGY_VERSION '$served'",
        )
    }

    @Then("every generated ontology file carries the do-not-edit header")
    fun everyGeneratedFileCarriesTheHeader() {
        listOf(SDL, TYPESCRIPT).forEach { path ->
            assertTrue(
                read(path).lineSequence().first().contains("GENERATED FROM ontology/v1 - DO NOT EDIT"),
                "$path does not announce itself as generated",
            )
        }
    }

    private fun servedNodeTypes(): List<String> = world.lastBody().path("nodeTypes").map { it.path("name").asText() }

    private fun servedEdgeTypes(): List<String> = world.lastBody().path("edgeTypes").map { it.path("name").asText() }

    /** Paths are relative to the backend project directory, which is the test's working directory. */
    private fun read(path: String): String = File(path).readText()

    private companion object {
        const val SNAPSHOT = "src/main/resources/ontology/v1/ontology.json"
        const val SDL = "src/main/resources/graphql/schema.generated.graphqls"
        const val TYPESCRIPT = "../frontend/src/generated/ontology.ts"
    }
}
