package com.repodatagraph.application.lifecycle

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.InvalidMigrationException
import com.repodatagraph.domain.lifecycle.MigrationChecksumException
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.lifecycle.OntologyVersion

/** The migrations a graph still needs, oldest first, and those it never needed. */
data class MigrationPlan(
    val pending: List<MigrationFile>,
    val baseline: List<MigrationFile>,
)

/**
 * Which shipped migrations a graph still needs (#33, FR7).
 *
 * A migration runs once, in version order, and only on a graph older than it. A graph that began on
 * its version or a newer one - a new graph included - never held the shape it repairs, so it is a
 * baseline: recorded as applied without running. A migration applied once must still be the file
 * that was applied, or the history the graph records is not the history it had.
 */
object MigrationPlanner {
    fun plan(
        files: List<MigrationFile>,
        registryVersion: OntologyVersion,
        dbVersion: OntologyVersion?,
        applied: List<AppliedMigration>,
        verifyChecksums: Boolean = true,
    ): MigrationPlan {
        requireOneMigrationPerVersion(files)
        requireNoneNewerThan(files, registryVersion)
        val appliedByVersion = applied.associateBy { OntologyVersion.parse(it.version) }
        if (verifyChecksums) requireUnchanged(files, appliedByVersion)

        val notApplied = files.filter { it.version !in appliedByVersion }.sortedBy { it.version }
        val (baseline, pending) = notApplied.partition { dbVersion == null || it.version <= dbVersion }
        return MigrationPlan(pending = pending, baseline = baseline)
    }

    private fun requireOneMigrationPerVersion(files: List<MigrationFile>) {
        val twins =
            files
                .groupBy { it.version }
                .filterValues { it.size > 1 }
                .entries
                .firstOrNull() ?: return
        throw InvalidMigrationException(
            "more than one migration brings the graph to ${twins.key}: ${twins.value.map { it.id }.sorted().joinToString()}",
        )
    }

    private fun requireNoneNewerThan(
        files: List<MigrationFile>,
        registryVersion: OntologyVersion,
    ) {
        val ahead = files.firstOrNull { it.version > registryVersion } ?: return
        throw InvalidMigrationException(
            "migration ${ahead.id} brings the graph to ${ahead.version}, newer than this build's ontology $registryVersion",
        )
    }

    private fun requireUnchanged(
        files: List<MigrationFile>,
        appliedByVersion: Map<OntologyVersion, AppliedMigration>,
    ) {
        files.forEach { file ->
            val record = appliedByVersion[file.version] ?: return@forEach
            if (record.checksum != file.checksum) throw MigrationChecksumException(file.id, record.checksum, file.checksum)
        }
    }
}
