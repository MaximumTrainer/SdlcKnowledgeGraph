package com.repodatagraph.application.connector

import com.repodatagraph.config.ConnectorsProperties
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.SyncMode
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component

/**
 * Runs each enabled connector on its own cron.
 *
 * Only enabled connectors are scheduled at all. A disabled one is still visible through the API, but
 * nothing is registered for it, so "disabled" means "makes no requests to anything" rather than
 * "runs and throws them away".
 *
 * A trigger that arrives while the previous run is still going is skipped, not queued. A connector
 * slower than its schedule would otherwise accumulate runs until they overlap permanently, and two
 * runs of one connector interleave their writes. Skipping is logged, because silently doing nothing
 * on a schedule is indistinguishable from a scheduler that has stopped.
 */
@Component
class SyncScheduler(
    private val registry: AdapterRegistry,
    private val syncService: SyncService,
    private val properties: ConnectorsProperties,
    private val taskScheduler: TaskScheduler,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun scheduleEnabledConnectors() {
        registry.enabled().forEach { registered ->
            val schedule = properties.settingsFor(registered.name).schedule
            taskScheduler.schedule({ runIfIdle(registered.name) }, CronTrigger(schedule))
            log.info("scheduled connector {} with cron {}", registered.name, schedule)
        }
    }

    private fun runIfIdle(name: String) {
        if (syncService.isRunning(name)) {
            log.info("skipping scheduled sync of {}: the previous run has not finished", name)
            return
        }
        val registered = registry.find(name) ?: return
        // Incremental when the connector can, so a schedule does not re-read the whole estate every
        // quarter of an hour. The watermark decides where it starts.
        val mode =
            if (registered.descriptor.supports(Capability.INCREMENTAL)) SyncMode.INCREMENTAL else SyncMode.FULL
        try {
            val runId = syncService.start(name, mode)
            syncService.execute(name, runId, mode)
        } catch (refused: SyncInProgressException) {
            log.info("skipping scheduled sync of {}: {}", name, refused.message)
        }
    }
}
