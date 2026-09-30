package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime

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
            writtenBy = "dan",
            principalType = "user",
        )

    private val byService =
        full.copy(writtenBy = "triage-agent", principalType = "service", onBehalfOfTeam = "team-payments")

    @Test
    fun `every field is flattened under the prov prefix`() {
        val properties = ProvenanceMapper.toProperties(full)

        assertEquals("github", properties["prov_sourceSystem"])
        assertEquals("acme/payments", properties["prov_sourceId"])
        assertEquals(ingested, (properties["prov_ingestedAt"] as ZonedDateTime).toInstant())
        assertEquals(observed, (properties["prov_observedAt"] as ZonedDateTime).toInstant())
        assertEquals(0.95, properties["prov_confidence"])
        assertEquals(true, properties["prov_inferred"])
        assertEquals(ingested, (properties["prov_validFrom"] as ZonedDateTime).toInstant())
        assertEquals("run-1", properties["prov_syncRunId"])
        assertEquals("dan", properties["prov_writtenBy"])
        assertEquals("user", properties["prov_principalType"])
    }

    @Test
    fun `who wrote a fact survives the round trip`() {
        val back = ProvenanceMapper.fromProperties(ProvenanceMapper.toProperties(full))

        assertEquals("dan", back.writtenBy)
        assertEquals("user", back.principalType)
    }

    @Test
    fun `the team a service acted for is stored under the prefix and survives the round trip (#115)`() {
        val properties = ProvenanceMapper.toProperties(byService)

        assertEquals("team-payments", properties["prov_onBehalfOfTeam"])
        assertEquals("team-payments", ProvenanceMapper.fromProperties(properties).onBehalfOfTeam)
    }

    @Test
    fun `a fact written before anyone was recorded reads back with no writer`() {
        val legacy = ProvenanceMapper.toProperties(full).filterKeys { it != "prov_writtenBy" && it != "prov_principalType" }

        val back = ProvenanceMapper.fromProperties(legacy)

        assertNull(back.writtenBy)
        assertNull(back.principalType)
    }

    @Test
    fun `timestamps are stored as zoned date times, which the Neo4j driver understands`() {
        val properties = ProvenanceMapper.toProperties(full)

        assertTrue(properties["prov_ingestedAt"] is ZonedDateTime, "the driver cannot convert an Instant")
        assertTrue(properties["prov_validFrom"] is ZonedDateTime, "the driver cannot convert an Instant")
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
