package com.repodatagraph.application.lifecycle

import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.domain.lifecycle.ArchiveResult
import com.repodatagraph.domain.lifecycle.ConnectorLifecycle
import com.repodatagraph.domain.lifecycle.LifecycleStatus
import com.repodatagraph.domain.lifecycle.MigrationApplyResult
import com.repodatagraph.domain.lifecycle.MigrationStatus
import com.repodatagraph.domain.lifecycle.NodeHistory
import com.repodatagraph.domain.lifecycle.VersioningPolicy
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.`in`.LifecycleUseCase
import com.repodatagraph.domain.port.out.FactLifecycle
import org.springframework.stereotype.Service

/** The data lifecycle's administration (#33), gathered from the services that do each part. */
@Service
class LifecycleService(
    private val migrator: OntologyMigrator,
    private val archive: ArchiveService,
    private val versioning: VersioningPolicy,
    private val connectors: AdapterRegistry,
    private val facts: FactLifecycle,
) : LifecycleUseCase {
    override fun status(): LifecycleStatus =
        LifecycleStatus(
            migrations = migrator.status(),
            versioning = versioning.settings.copy(excludedTypes = versioning.excludedTypes()),
            archive = archive.status(),
            connectors =
                connectors.all().map {
                    ConnectorLifecycle(
                        name = it.name,
                        sourceSystem = it.descriptor.sourceSystem,
                        missingFromFullSync = it.tombstoneRules.missingFromFullSync,
                        gracePeriod = it.tombstoneRules.gracePeriod,
                        fullSyncIsComplete = it.descriptor.fullSyncIsComplete,
                    )
                },
        )

    override fun migrations(): MigrationStatus = migrator.status()

    override fun applyMigrations(): MigrationApplyResult = migrator.applyPending()

    override fun archive(dryRun: Boolean): ArchiveResult = archive.run(dryRun)

    override fun history(key: NodeKey): NodeHistory? = facts.history(key)
}
