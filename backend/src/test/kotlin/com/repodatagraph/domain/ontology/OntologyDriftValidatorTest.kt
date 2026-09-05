package com.repodatagraph.domain.ontology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * The registry is the source of truth, but the nine core types also have Kotlin classes so the
 * hand-written traversal code keeps compile-time safety. This validator is what stops the two
 * definitions drifting apart unnoticed: it runs at startup and refuses to boot on a mismatch.
 */
class OntologyDriftValidatorTest {
    private data class SampleTeam(
        val id: String,
        val name: String,
        val email: String?,
    )

    private fun registryWith(properties: List<PropertyDef>) =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef(
                        name = "SampleTeam",
                        description = null,
                        identity = listOf("name"),
                        properties = properties,
                    ),
                ),
            edgeTypes = emptyList(),
        )

    @Test
    fun `a class matching its registry entry passes`() {
        val registry =
            registryWith(
                listOf(
                    PropertyDef("name", PropertyType.STRING, required = true),
                    PropertyDef("email", PropertyType.STRING, required = false),
                ),
            )

        assertDoesNotThrow {
            OntologyDriftValidator(registry).validate(mapOf("SampleTeam" to SampleTeam::class))
        }
    }

    @Test
    fun `a required registry property missing from the class fails startup`() {
        val registry =
            registryWith(
                listOf(
                    PropertyDef("name", PropertyType.STRING, required = true),
                    PropertyDef("slug", PropertyType.STRING, required = true),
                    PropertyDef("email", PropertyType.STRING, required = false),
                ),
            )

        val error =
            assertThrows<OntologyDriftException> {
                OntologyDriftValidator(registry).validate(mapOf("SampleTeam" to SampleTeam::class))
            }

        assertTrue(error.message!!.contains("SampleTeam.slug"), error.message)
    }

    @Test
    fun `a class property the registry never declared fails startup`() {
        val registry = registryWith(listOf(PropertyDef("name", PropertyType.STRING, required = true)))

        val error =
            assertThrows<OntologyDriftException> {
                OntologyDriftValidator(registry).validate(mapOf("SampleTeam" to SampleTeam::class))
            }

        assertTrue(error.message!!.contains("SampleTeam.email"), error.message)
    }

    @Test
    fun `an optional registry property may be absent from the class`() {
        val registry =
            registryWith(
                listOf(
                    PropertyDef("name", PropertyType.STRING, required = true),
                    PropertyDef("email", PropertyType.STRING, required = false),
                    PropertyDef("url", PropertyType.STRING, required = false),
                ),
            )

        assertDoesNotThrow {
            OntologyDriftValidator(registry).validate(mapOf("SampleTeam" to SampleTeam::class))
        }
    }

    @Test
    fun `a class with no registry entry is reported, not skipped`() {
        val registry = registryWith(listOf(PropertyDef("name", PropertyType.STRING, required = true)))

        val error =
            assertThrows<OntologyDriftException> {
                OntologyDriftValidator(registry).validate(mapOf("Ghost" to SampleTeam::class))
            }

        assertTrue(error.message!!.contains("Ghost"), error.message)
    }

    @Test
    fun `every problem is reported at once so the whole mismatch is visible`() {
        val registry =
            registryWith(
                listOf(
                    PropertyDef("name", PropertyType.STRING, required = true),
                    PropertyDef("slug", PropertyType.STRING, required = true),
                    PropertyDef("tier", PropertyType.STRING, required = true),
                ),
            )

        val error =
            assertThrows<OntologyDriftException> {
                OntologyDriftValidator(registry).validate(mapOf("SampleTeam" to SampleTeam::class))
            }

        assertTrue(error.message!!.contains("SampleTeam.slug"), error.message)
        assertTrue(error.message!!.contains("SampleTeam.tier"), error.message)
        assertTrue(error.message!!.contains("SampleTeam.email"), error.message)
    }

    @Test
    fun `the id property is structural and not expected in the registry`() {
        val registry =
            registryWith(
                listOf(
                    PropertyDef("name", PropertyType.STRING, required = true),
                    PropertyDef("email", PropertyType.STRING, required = false),
                ),
            )

        assertDoesNotThrow {
            OntologyDriftValidator(registry).validate(mapOf("SampleTeam" to SampleTeam::class))
        }
        assertEquals(1, registry.allNodeTypes().size)
    }
}
