package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Neo4j cannot store a nested map on a node, so provenance is flattened to prefixed properties.
 * The prefix keeps it from colliding with domain properties of the same name.
 */
class ProvenanceMapperTest {
    private val ingested = Instant.parse("2026-02-03T10:15:30Z")
    private val observed = Instant.parse("2026-02-03T10:00:00Z")

    private val full =
        Provenance(
            sourceSystem = "github",
            sourceId = "acme/payments",
            ingestedAt = ingested,
            observedAt = observed,
            confidence = 0.95,
            inferred = true,
            validFrom = ingested,
            validTo = null,
            syncRunId = "run-1",
        )

    @Test
    fun `every field is flattened under the prov prefix`() {
        val properties = ProvenanceMapper.toProperties(full)

        assertEquals("github", properties["prov_sourceSystem"])
        assertEquals("acme/payments", properties["prov_sourceId"])
        assertEquals(ingested, properties["prov_ingestedAt"])
        assertEquals(observed, properties["prov_observedAt"])
        assertEquals(0.95, properties["prov_confidence"])
        assertEquals(true, properties["prov_inferred"])
        assertEquals(ingested, properties["prov_validFrom"])
        assertEquals("run-1", properties["prov_syncRunId"])
    }

    @Test
    fun `no property escapes the prefix, so domain properties cannot be overwritten`() {
        val unprefixed = ProvenanceMapper.toProperties(full).keys.filterNot { it.startsWith("prov_") }

        assertTrue(unprefixed.isEmpty(), "unprefixed keys: $unprefixed")
    }

    @Test
    fun `a full record survives a round trip`() {
        assertEquals(full, ProvenanceMapper.fromProperties(ProvenanceMapper.toProperties(full)))
    }

    @Test
    fun `absent optional fields round trip as null rather than as the string null`() {
        val minimal = Provenance.manual(ingested)

        val properties = ProvenanceMapper.toProperties(minimal)
        assertNull(properties["prov_sourceId"])
        assertNull(properties["prov_validTo"])

        assertEquals(minimal, ProvenanceMapper.fromProperties(properties))
    }

    @Test
    fun `a manual write is fully trusted and not marked inferred`() {
        val manual = Provenance.manual(ingested)

        assertEquals("manual", manual.sourceSystem)
        assertEquals(1.0, manual.confidence)
        assertFalse(manual.inferred)
        assertEquals(ingested, manual.validFrom)
        assertNull(manual.validTo)
    }

    @Test
    fun `confidence outside zero to one is rejected at construction`() {
        val tooHigh =
            runCatching {
                Provenance(sourceSystem = "x", ingestedAt = ingested, validFrom = ingested, confidence = 1.5)
            }
        val tooLow =
            runCatching {
                Provenance(sourceSystem = "x", ingestedAt = ingested, validFrom = ingested, confidence = -0.1)
            }

        assertTrue(tooHigh.isFailure)
        assertTrue(tooLow.isFailure)
    }

    @Test
    fun `a blank source system is rejected, because provenance without an origin is not provenance`() {
        val blank =
            runCatching {
                Provenance(sourceSystem = " ", ingestedAt = ingested, validFrom = ingested)
            }

        assertTrue(blank.isFailure)
    }

    @Test
    fun `properties that are not provenance are ignored when reading back`() {
        val mixed = ProvenanceMapper.toProperties(full) + mapOf("name" to "payments", "topics" to listOf("a"))

        assertEquals(full, ProvenanceMapper.fromProperties(mixed))
    }
}
