package com.repodatagraph.application.connector

import com.repodatagraph.config.observability.ObservabilityProperties
import com.repodatagraph.domain.port.out.SyncRunStore
import com.repodatagraph.observability.LogEvents
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Forgets sync runs once they are older than `observability.sync-run-retention` (#29, FR6).
 *
 * Every run leaves a SyncRun node and a PRODUCED edge to each node it wrote, so without this the
 * history grows with every sync for as long as the instance runs. The nodes a pruned run produced
 * stay, still naming it in their provenance: forgetting the run must not forget where a fact came
 * from.
 *
 * Deleted a batch at a time, until a batch comes back short, so a first prune of a year of history
 * is many small transactions rather than one the database has to hold in memory.
 *
 * This is an internal write, not an HTTP request, so a read-only deployment (#48) prunes too: the
 * read-only posture guards the API, and an instance nobody may write to still should not fill up.
 */
@Component
class SyncRunRetentionJob(
    private val store: SyncRunStore,
    private val clock: Clock,
    private val properties: ObservabilityProperties,
    private val taskScheduler: TaskScheduler,
) {
    /** How far the prune in progress has got, so a failure can say what it had already done. */
    @Volatile
    private var progress = 0

    @EventListener(ApplicationReadyEvent::class)
    fun schedule() {
        taskScheduler.schedule({ pruneAndReport() }, CronTrigger(properties.syncRunRetentionCron))
    }

    /**
     * Deletes every finished run older than the retention, and says how many went. Public so a test
     * can run tonight's prune now instead of waiting for the cron.
     */
    fun prune(): Int {
        val cutoff = Instant.now(clock).minus(properties.syncRunRetention)
        var deleted = 0
        do {
            val batch = store.deleteFinishedBefore(cutoff, BATCH_SIZE)
            deleted += batch
            progress = deleted
        } while (batch >= BATCH_SIZE)
        LogEvents.syncRunsPruned(deleted, cutoff)
        return deleted
    }

    /**
     * The scheduled entry point. A failure is logged as an event and not rethrown: the scheduler would
     * only hand it to a generic handler, and tomorrow night's run is the retry.
     */
    private fun pruneAndReport() {
        progress = 0
        try {
            prune()
        } catch (
            // Whatever the store throws - Neo4j unreachable, a timeout - the answer is the same.
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            LogEvents.syncRunsPruneFailed(progress, failure)
        }
    }

    companion object {
        /** Runs deleted per transaction; small enough for a 512 MB instance, large enough to be quick. */
        const val BATCH_SIZE = 1000
    }
}
