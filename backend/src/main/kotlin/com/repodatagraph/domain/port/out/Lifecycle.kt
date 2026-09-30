package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.ArchiveCounts
import com.repodatagraph.domain.lifecycle.ArchiveRecord
import com.repodatagraph.domain.lifecycle.CompiledStatement
import com.repodatagraph.domain.lifecycle.MigrationFile
import java.time.Instant

/** The migration files this build ships (#33, FR7). */
fun interface MigrationSource {
    fun migrations(): List<MigrationFile>
}

/** Which ontology version the graph is on, and which migrations it has had. */
interface OntologyStateStore {
    /** The version each Ontology node states; one, normally, and none on a graph never started. */
    fun storedVersions(): List<String>

    fun applied(): List<AppliedMigration>

    /**
     * Runs [statements] and records [migration] as applied, moving the graph's version to the
     * migration's, all in one transaction: on any failure none of it happened.
     */
    fun apply(
        migration: MigrationFile,
        statements: List<CompiledStatement>,
        appliedAt: Instant,
    ): AppliedMigration

    /** Records [migration] as applied without running it, for a graph that never needed it. */
    fun recordBaseline(
        migration: MigrationFile,
        at: Instant,
    )

    /** States that the graph is on [version], leaving exactly one Ontology node. */
    fun recordVersion(
        version: String,
        at: Instant,
    )
}

/**
 * The closed facts old enough to archive (#33, FR6): every node of a domain type closed before the
 * cutoff, with its relationships, and every relationship closed before it.
 */
interface ArchiveStore {
    fun count(cutoff: Instant): ArchiveCounts

    /** Writes each fact [count] counts to [sink], changing nothing. */
    fun export(
        cutoff: Instant,
        sink: ArchiveSink,
    ): ArchiveCounts

    /** Deletes each fact [count] counts, with the versions of each node it deletes. */
    fun purge(cutoff: Instant): ArchiveCounts
}

/** Where an archive run writes. */
fun interface ArchiveWriter {
    @Throws(java.io.IOException::class)
    fun open(at: Instant): ArchiveSink
}

/** One archive file being written. */
interface ArchiveSink : AutoCloseable {
    /** Where the file is, as the run's result names it. */
    val location: String

    fun write(record: ArchiveRecord)
}
