package com.repodatagraph.domain.lifecycle

import java.security.MessageDigest
import java.time.Instant

/** Whether the application applies pending migrations as it starts, or waits for an admin (#33, FR8). */
enum class MigrationMode(
    val wireName: String,
) {
    AUTO("auto"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromWire(value: String): MigrationMode =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
                ?: throw IllegalArgumentException("lifecycle.migrations.mode is one of ${entries.map { it.wireName }}, not '$value'")
    }
}

/** How a migration is written: declarative operations the registry can check, or raw Cypher. */
enum class MigrationFormat {
    YAML,
    CYPHER,
}

/**
 * An ontology version, compared by number: 1.10.0 is newer than 1.4.0. Missing parts read as zero,
 * so "1.4" is "1.4.0", and a part that is not a number reads as zero too, as #85's check did.
 */
data class OntologyVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<OntologyVersion> {
    override fun compareTo(other: OntologyVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    /** As a migration's file name spells it: `1_4_0`. */
    val underscored: String get() = "${major}_${minor}_$patch"

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private const val PARTS = 3

        fun parse(value: String): OntologyVersion {
            val parts = value.trim().split('.').map { it.toIntOrNull() ?: 0 }
            val padded = (parts + List(PARTS) { 0 }).take(PARTS)
            return OntologyVersion(padded[0], padded[1], padded[2])
        }
    }
}

/** One statement of a migration, with any values it takes as parameters rather than as text. */
data class CompiledStatement(
    val cypher: String,
    val parameters: Map<String, Any?> = emptyMap(),
)

/**
 * A shipped migration file (#33, FR7): `V<major>_<minor>_<patch>__<name>.yaml`, or `.cypher` for a
 * raw one. The version is the ontology version it brings the graph to.
 */
data class MigrationFile(
    val fileName: String,
    val version: OntologyVersion,
    val name: String,
    val format: MigrationFormat,
    val content: String,
) {
    /** The file name without its extension: how logs, errors and the API name the migration. */
    val id: String get() = "V${version.underscored}__$name"

    /** What says, later, whether the file that was applied is the file still shipped. */
    val checksum: String get() = MigrationChecksum.of(content)

    companion object {
        private const val MAJOR = 1
        private const val MINOR = 2
        private const val PATCH = 3
        private const val NAME_PART = 4
        private const val EXTENSION = 5
        private val NAME = Regex("^V(\\d+)_(\\d+)_(\\d+)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.(yaml|cypher)$")

        fun parse(
            fileName: String,
            content: String,
        ): MigrationFile {
            val match =
                NAME.matchEntire(fileName)
                    ?: throw InvalidMigrationException(
                        "migration file '$fileName' is not named V<major>_<minor>_<patch>__<lower_snake_name>.yaml or .cypher",
                    )
            val parts = match.groupValues
            return MigrationFile(
                fileName = fileName,
                version = OntologyVersion(parts[MAJOR].toInt(), parts[MINOR].toInt(), parts[PATCH].toInt()),
                name = parts[NAME_PART],
                format = if (parts[EXTENSION] == "cypher") MigrationFormat.CYPHER else MigrationFormat.YAML,
                content = content,
            )
        }
    }
}

/** SHA-256 of a migration's content, the same however the checkout ends its lines. */
object MigrationChecksum {
    fun of(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** A migration the graph has not had yet. */
data class MigrationInfo(
    val version: String,
    val name: String,
    val checksum: String,
    val description: String?,
)

/**
 * A migration the graph has had, as recorded on it. A [baseline] one was never run: the graph began
 * on that version or a newer one, so it never held the shape the migration repairs.
 */
data class AppliedMigration(
    val version: String,
    val name: String,
    val checksum: String,
    val appliedAt: Instant,
    val durationMs: Long,
    val baseline: Boolean,
) {
    val id: String get() = "V${OntologyVersion.parse(version).underscored}__$name"
}

/** Which ontology version the graph is on, beside the one this build ships, and what lies between. */
data class MigrationStatus(
    val registryVersion: String,
    val dbVersion: String?,
    val mode: MigrationMode,
    val pending: List<MigrationInfo>,
    val applied: List<AppliedMigration>,
) {
    val upToDate: Boolean get() = pending.isEmpty() && dbVersion == registryVersion
}

/** What applying the pending migrations did. */
data class MigrationApplyResult(
    val applied: List<AppliedMigration>,
    val dbVersion: String?,
)

/** A migration file that cannot be what it says: misnamed, malformed, or naming what the registry does not declare. */
class InvalidMigrationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** A migration applied once whose shipped file has changed since (#33, FR7). */
class MigrationChecksumException(
    val migration: String,
    val recorded: String,
    val actual: String,
) : IllegalStateException(
        "migration $migration was applied with checksum $recorded but the shipped file's checksum is $actual; " +
            "an applied migration is never edited - ship a new one instead",
    )

/** A migration that failed; its transaction was rolled back, so the graph is as it was before it. */
class MigrationFailedException(
    val migration: String,
    cause: Throwable,
) : IllegalStateException("migration $migration failed and was rolled back: ${cause.message}", cause)

/** A graph written by a newer ontology than this build understands (#85). */
class OntologyAheadException(
    val dbVersion: String,
    val registryVersion: String,
) : IllegalStateException(
        "the graph was written with ontology version $dbVersion but this build only understands $registryVersion; " +
            "upgrade the application or run the migration for that version",
    )
