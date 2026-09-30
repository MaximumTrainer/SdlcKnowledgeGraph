package com.repodatagraph.config.lifecycle

import com.repodatagraph.adapter.out.archive.FilesystemArchiveWriter
import com.repodatagraph.adapter.out.neo4j.SCHEMA_INITIALIZER_ORDER
import com.repodatagraph.adapter.out.ontology.ClasspathMigrationSource
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.lifecycle.ArchiveService
import com.repodatagraph.application.lifecycle.OntologyMigrator
import com.repodatagraph.domain.lifecycle.VersioningPolicy
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.ArchiveStore
import com.repodatagraph.domain.port.out.MigrationSource
import com.repodatagraph.domain.port.out.OntologyStateStore
import com.repodatagraph.observability.LogEvents
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.core.io.ResourceLoader
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import java.time.Clock

/** The data lifecycle's parts (#33), built from `lifecycle.*`. */
@Configuration
@EnableConfigurationProperties(LifecycleProperties::class)
class LifecycleConfig(
    private val properties: LifecycleProperties,
) {
    @Bean
    fun versioningPolicy(registry: OntologyRegistry): VersioningPolicy = VersioningPolicy(properties.versioning.settings(), registry)

    @Bean
    fun migrationSource(resourceLoader: ResourceLoader): MigrationSource =
        ClasspathMigrationSource(properties.migrations.location, resourceLoader)

    @Bean
    fun ontologyMigrator(
        source: MigrationSource,
        store: OntologyStateStore,
        registry: OntologyRegistry,
        clock: Clock,
    ): OntologyMigrator = OntologyMigrator(source, store, registry, properties.migrations.migrationMode(), clock)

    @Bean
    fun archiveService(
        store: ArchiveStore,
        runs: SyncRunRecorder,
        clock: Clock,
    ): ArchiveService {
        val settings = properties.archive.settings()
        return ArchiveService(settings, store, FilesystemArchiveWriter(settings.directory), runs, clock)
    }
}

/**
 * Brings the graph to this build's ontology as the application starts (#33, FR8), once the schema's
 * constraints exist, in place of #85's bare version check, which it still makes. A failure stops the
 * application - after logging the migration that failed - rather than serving data in a shape the
 * build does not read; readiness and liveness are otherwise never touched by it.
 */
@Component
@Order(SCHEMA_INITIALIZER_ORDER + 1)
class OntologyMigrationStartup(
    private val migrator: OntologyMigrator,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun migrate() = migrator.migrate()
}

/**
 * Runs the archive on its schedule, and exists only when `lifecycle.archive.enabled` is true: an
 * archive nobody enabled is never scheduled. Even then it runs in its configured mode, a dry run by
 * default. A failure is logged and not rethrown; the next run is the retry.
 */
@Component
@ConditionalOnProperty(prefix = "lifecycle.archive", name = ["enabled"], havingValue = "true")
class ArchiveScheduler(
    private val archive: ArchiveService,
    private val properties: LifecycleProperties,
    private val taskScheduler: TaskScheduler,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun schedule() {
        val cron = properties.archive.schedule
        if (cron == CRON_DISABLED) return
        taskScheduler.schedule({ runAndReport() }, CronTrigger(cron))
        LogEvents.lifecycleArchiveScheduled(
            cron,
            properties.archive
                .settings()
                .mode.wireName,
        )
    }

    private fun runAndReport() {
        try {
            archive.run(dryRun = false)
        } catch (
            // Neo4j unreachable, a full disk: the answer is the same, log it and try tomorrow.
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            LogEvents.lifecycleArchiveFailed(properties.archive.mode, failure)
        } catch (failure: java.io.IOException) {
            LogEvents.lifecycleArchiveFailed(properties.archive.mode, failure)
        }
    }

    private companion object {
        /** Spring's own spelling of "never", as `@Scheduled(cron = "-")` takes it. */
        const val CRON_DISABLED = "-"
    }
}
