package com.repodatagraph.application

import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

/**
 * Validation is read from the registry, so this is a table over the declared property types rather
 * than a rule per type. A type added to the ontology is validated without touching this class.
 *
 * One validator serves nodes and edges: the registry declares their properties with the same shape,
 * so a second implementation would only be a second place for the rules to drift.
 */
class PropertyValidatorTest {
    private val validator = PropertyValidator()

    private val withEnum =
        listOf(
            PropertyDef(
                "kind",
                PropertyType.STRING,
                required = true,
                description = "What sort of dependency this is",
                enum = listOf("library", "api", "event", "data"),
            ),
        )

    private val everyType =
        NodeTypeDef(
            name = "Sample",
            description = null,
            identity = listOf("name"),
            properties =
                listOf(
                    PropertyDef("name", PropertyType.STRING, required = true),
                    PropertyDef("count", PropertyType.INT),
                    PropertyDef("enabled", PropertyType.BOOLEAN),
                    PropertyDef("seenAt", PropertyType.INSTANT),
                    PropertyDef("topics", PropertyType.STRING_ARRAY),
                ),
        )

    @Test
    fun `a fully populated node has nothing wrong with it`() {
        val errors =
            validator.validate(
                everyType,
                mapOf(
                    "name" to "sample",
                    "count" to 3,
                    "enabled" to true,
                    "seenAt" to "2026-01-01T00:00:00Z",
                    "topics" to listOf("a", "b"),
                ),
            )

        assertThat(errors).isEmpty()
    }

    @Test
    fun `a missing required property is reported against its own field`() {
        val errors = validator.validate(everyType.properties, mapOf("count" to 1))

        assertThat(errors).containsExactly(PropertyError("name", "name is required"))
    }

    @Test
    fun `a required property present but blank is still missing`() {
        val errors = validator.validate(everyType.properties, mapOf("name" to "   "))

        assertThat(errors).containsExactly(PropertyError("name", "name is required"))
    }

    @Test
    fun `a property the ontology does not declare is refused`() {
        val errors = validator.validate(everyType.properties, mapOf("name" to "sample", "colour" to "blue"))

        assertThat(errors).containsExactly(PropertyError("colour", "not in ontology"))
    }

    @Test
    fun `identity and provenance cannot be supplied by the client`() {
        val errors = validator.validate(everyType.properties, mapOf("name" to "sample", "id" to "Sample:x", "key" to "x"))

        assertThat(errors.map { it.field }).containsExactlyInAnyOrder("id", "key")
    }

    @ParameterizedTest
    @CsvSource(
        "count, notANumber, expected int",
        "count, true, expected int",
        "enabled, notABoolean, expected boolean",
        "seenAt, notAnInstant, expected instant",
        "topics, notAList, expected string[]",
    )
    fun `a value of the wrong type names the type the ontology declares`(
        field: String,
        value: String,
        expectedMessage: String,
    ) {
        val errors = validator.validate(everyType.properties, mapOf("name" to "sample", field to value))

        assertThat(errors).containsExactly(PropertyError(field, expectedMessage))
    }

    @Test
    fun `a string list with a non-string element is refused`() {
        val errors = validator.validate(everyType.properties, mapOf("name" to "sample", "topics" to listOf("a", 1)))

        assertThat(errors).containsExactly(PropertyError("topics", "expected string[]"))
    }

    @Test
    fun `an Instant is accepted as well as its string form`() {
        val errors = validator.validate(everyType.properties, mapOf("name" to "sample", "seenAt" to Instant.EPOCH))

        assertThat(errors).isEmpty()
    }

    @Test
    fun `an optional property that is absent is not an error, and neither is an explicit null`() {
        val errors = validator.validate(everyType.properties, mapOf("name" to "sample", "count" to null))

        assertThat(errors).isEmpty()
    }

    @Test
    fun `every problem is reported at once, so a form can show them all`() {
        val errors = validator.validate(everyType.properties, mapOf("count" to "x", "colour" to "blue"))

        assertThat(errors.map { it.field }).containsExactlyInAnyOrder("name", "count", "colour")
    }

    @Test
    fun `a value outside a declared enum is refused, and the message lists what is allowed`() {
        val errors = validator.validate(withEnum, mapOf("kind" to "magic"))

        assertThat(errors).containsExactly(PropertyError("kind", "kind must be one of library, api, event, data"))
    }

    @Test
    fun `a value inside the enum is accepted`() {
        assertThat(validator.validate(withEnum, mapOf("kind" to "event"))).isEmpty()
    }

    @Test
    fun `an enum property that is required is still required`() {
        val errors = validator.validate(withEnum, emptyMap())

        assertThat(errors).containsExactly(PropertyError("kind", "kind is required"))
    }

    @Test
    fun `an enum is case sensitive, because the stored value is what a query will match on`() {
        val errors = validator.validate(withEnum, mapOf("kind" to "Library"))

        assertThat(errors).hasSize(1)
    }
}
