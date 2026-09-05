package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.Provenance
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Flattens [Provenance] onto node and relationship properties.
 *
 * Neo4j properties are scalars or arrays of scalars, so a nested provenance object cannot be stored
 * directly. Every field is written with a `prov_` prefix, which also guarantees provenance can never
 * overwrite a domain property that happens to share a name.
 */
object ProvenanceMapper {
    private const val PREFIX = "prov_"

    private const val SOURCE_SYSTEM = "${PREFIX}sourceSystem"
    private const val SOURCE_ID = "${PREFIX}sourceId"
    private const val INGESTED_AT = "${PREFIX}ingestedAt"
    private const val OBSERVED_AT = "${PREFIX}observedAt"
    private const val CONFIDENCE = "${PREFIX}confidence"
    private const val INFERRED = "${PREFIX}inferred"
    private const val VALID_FROM = "${PREFIX}validFrom"
    private const val VALID_TO = "${PREFIX}validTo"
    private const val SYNC_RUN_ID = "${PREFIX}syncRunId"

    fun toProperties(provenance: Provenance): Map<String, Any?> =
        mapOf(
            SOURCE_SYSTEM to provenance.sourceSystem,
            SOURCE_ID to provenance.sourceId,
            INGESTED_AT to storable(provenance.ingestedAt),
            OBSERVED_AT to storable(provenance.observedAt),
            CONFIDENCE to provenance.confidence,
            INFERRED to provenance.inferred,
            VALID_FROM to storable(provenance.validFrom),
            VALID_TO to storable(provenance.validTo),
            SYNC_RUN_ID to provenance.syncRunId,
        )

    /**
     * The Neo4j driver has no mapping for [Instant], so timestamps are stored as UTC
     * [ZonedDateTime]. Writing an Instant directly fails at query time with "Unable to convert
     * java.time.Instant to Neo4j Value".
     */
    fun storable(instant: Instant?): ZonedDateTime? = instant?.atZone(ZoneOffset.UTC)

    /** Reads provenance back, ignoring any domain properties in the same map. */
    fun fromProperties(properties: Map<String, Any?>): Provenance =
        Provenance(
            sourceSystem = properties[SOURCE_SYSTEM]?.toString() ?: Provenance.MANUAL,
            sourceId = properties[SOURCE_ID]?.toString(),
            ingestedAt = instant(properties[INGESTED_AT]) ?: Instant.EPOCH,
            observedAt = instant(properties[OBSERVED_AT]),
            confidence = properties[CONFIDENCE]?.toString()?.toDoubleOrNull() ?: Provenance.FULL_CONFIDENCE,
            inferred = properties[INFERRED]?.toString().toBoolean(),
            validFrom = instant(properties[VALID_FROM]) ?: Instant.EPOCH,
            validTo = instant(properties[VALID_TO]),
            syncRunId = properties[SYNC_RUN_ID]?.toString(),
        )

    fun isProvenanceProperty(name: String): Boolean = name.startsWith(PREFIX)

    private fun instant(value: Any?): Instant? =
        when (value) {
            null -> null
            is Instant -> value
            is ZonedDateTime -> value.toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            is java.time.LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            else -> runCatching { Instant.parse(value.toString()) }.getOrNull()
        }
}
