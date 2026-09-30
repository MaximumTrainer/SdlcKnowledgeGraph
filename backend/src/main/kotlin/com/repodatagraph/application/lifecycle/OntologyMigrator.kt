package com.repodatagraph.application.lifecycle

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.MigrationApplyResult
import com.repodatagraph.domain.lifecycle.MigrationFailedException
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.lifecycle.MigrationInfo
import com.repodatagraph.domain.lifecycle.MigrationMode
import com.repodatagraph.domain.lifecycle.MigrationStatus
import com.repodatagraph.domain.lifecycle.OntologyAheadException
import com.repodatagraph.domain.lifecycle.OntologyVersion
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.MigrationSource
import com.repodatagraph.domain.port.out.OntologyStateStore
import com.repodatagraph.observability.LogEvents
import java.time.Clock
import java.time.Instant

/**
 * Brings the graph's data to the ontology this build ships (#33, FR7-FR8), and records which
 * version it is on (#85).
 *
 * At startup it refuses a graph a newer ontology wrote, as #85 did, records the migrations a graph
 * never needed as baselines, and in [MigrationMode.AUTO] applies the pending ones in version order,
 * each in its own transaction. A failing one is rolled back, logged by name, and stops the
 * application: starting on data in a shape the build does not read would be worse. In
 * [MigrationMode.MANUAL] it logs what is pending and starts, leaving them to an admin; readiness is
 * never affected either way. The graph is recorded on the build's version only once nothing is
 * pending, so the version stated on the graph is always the shape its data is in.
 */
class OntologyMigrator(
    private val source: MigrationSource,
    private val store: OntologyStateStore,
    private val registry: OntologyRegistry,
    private val mode: MigrationMode,
    private val clock: Clock,
) {
    private val compiler = YamlMigrationCompiler(registry)
    private val registryVersion = OntologyVersion.parse(registry.version)

    fun migrate() {
        val plan = prepare()
        if (mode == MigrationMode.AUTO || plan.pending.isEmpty()) {
            applyAll(plan.pending)
        } else {
            LogEvents.ontologyMigrationsPending(dbVersion().orEmpty(), registry.version, plan.pending.map { it.id })
        }
    }

    /** Applies every pending migration now, whatever the mode: what an admin's request does. */
    @Synchronized
    fun applyPending(): MigrationApplyResult {
        val applied = applyAll(prepare().pending)
        return MigrationApplyResult(applied, dbVersion())
    }

    fun status(): MigrationStatus {
        val files = source.migrations()
        val applied = store.applied().sortedBy { OntologyVersion.parse(it.version) }
        val plan = MigrationPlanner.plan(files, registryVersion, parsedDbVersion(), applied, verifyChecksums = false)
        return MigrationStatus(
            registryVersion = registry.version,
            dbVersion = dbVersion(),
            mode = mode,
            pending = plan.pending.map { MigrationInfo(it.version.toString(), it.name, it.checksum, compiler.description(it)) },
            applied = applied,
        )
    }

    /** Refuses a newer graph, plans, and records the baselines, before anything runs. */
    private fun prepare(): MigrationPlan {
        val dbVersion = parsedDbVersion()
        if (dbVersion != null && dbVersion > registryVersion) throw OntologyAheadException(dbVersion.toString(), registry.version)

        val plan = MigrationPlanner.plan(source.migrations(), registryVersion, dbVersion, store.applied())
        // Every pending migration is compiled before the first runs, so one that cannot mean anything
        // is refused while the graph is still as it was.
        plan.pending.forEach { compiler.compile(it) }
        plan.baseline.forEach {
            store.recordBaseline(it, Instant.now(clock))
            LogEvents.ontologyMigrationBaselined(it.id)
        }
        return plan
    }

    private fun applyAll(pending: List<MigrationFile>): List<AppliedMigration> {
        val applied = pending.map(::applyOne)
        store.recordVersion(registry.version, Instant.now(clock))
        LogEvents.ontologyVersionRecorded(registry.version)
        return applied
    }

    private fun applyOne(migration: MigrationFile): AppliedMigration {
        val statements = compiler.compile(migration)
        val applied =
            try {
                store.apply(migration, statements, Instant.now(clock))
            } catch (
                // Whatever the database said - a syntax error, a constraint - the transaction was
                // rolled back, and the answer is the same: name the migration and stop.
                @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
            ) {
                LogEvents.ontologyMigrationFailed(migration.id, failure)
                throw MigrationFailedException(migration.id, failure)
            }
        LogEvents.ontologyMigrationApplied(migration.id, statements.size, applied.durationMs.toInt())
        return applied
    }

    private fun parsedDbVersion(): OntologyVersion? = store.storedVersions().map(OntologyVersion::parse).maxOrNull()

    private fun dbVersion(): String? = parsedDbVersion()?.toString()
}
