package com.repodatagraph.application.connector

import com.repodatagraph.config.observability.ObservabilityProperties
import com.repodatagraph.domain.model.SyncRun
import com.repodatagraph.domain.model.SyncRunPage
import com.repodatagraph.domain.model.SyncRunQuery
import com.repodatagraph.domain.port.out.SyncRunStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The nightly prune of old sync runs (#29, FR6): it asks the store for runs finished longer ago than
 * the retention, a bounded batch at a time, until a batch comes back short - so a year of history
 * is deleted in pieces the database can hold in memory, not in one transaction.
 */
class SyncRunRetentionJobTest {
    private val now = Instant.parse("2026-09-29T03:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Answers each delete with the next count it was given, and remembers what it was asked. */
    private class ScriptedStore(
        vararg counts: Int,
    ) : SyncRunStore {
        private val remaining = ArrayDeque(counts.toList())
        val asked = mutableListOf<Pair<Instant, Int>>()
        var failure: RuntimeException? = null

        override fun find(query: SyncRunQuery) = SyncRunPage(emptyList(), 0)

        override fun findById(id: String): SyncRun? = null

        override fun lastSuccessBySource(): Map<String, Instant> = emptyMap()

        override fun deleteFinishedBefore(
            cutoff: Instant,
            batchSize: Int,
        ): Int {
            failure?.let { throw it }
            asked += cutoff to batchSize
            return remaining.removeFirstOrNull() ?: 0
        }
    }

    private fun job(
        store: SyncRunStore,
        properties: ObservabilityProperties = ObservabilityProperties(),
        scheduler: TaskScheduler = mock(),
    ) = SyncRunRetentionJob(store, clock, properties, scheduler)

    @Test
    fun `deletes runs finished more than thirty days ago by default`() {
        val store = ScriptedStore(4)

        val deleted = job(store).prune()

        assertThat(deleted).isEqualTo(4)
        assertThat(store.asked).containsExactly(now.minus(Duration.ofDays(30)) to SyncRunRetentionJob.BATCH_SIZE)
    }

    @Test
    fun `keeps deleting in batches until a batch comes back short`() {
        val batch = SyncRunRetentionJob.BATCH_SIZE
        val store = ScriptedStore(batch, batch, 3)

        val deleted = job(store).prune()

        assertThat(deleted).isEqualTo(2 * batch + 3)
        assertThat(store.asked).hasSize(3)
    }

    @Test
    fun `stops at once when there is nothing to prune`() {
        val store = ScriptedStore(0)

        assertThat(job(store).prune()).isZero()
        assertThat(store.asked).hasSize(1)
    }

    @Test
    fun `honours a configured retention`() {
        val store = ScriptedStore(0)

        job(store, ObservabilityProperties(syncRunRetention = Duration.ofDays(7))).prune()

        assertThat(store.asked.single().first).isEqualTo(now.minus(Duration.ofDays(7)))
    }

    @Test
    fun `is scheduled on the configured cron`() {
        val scheduler: TaskScheduler = mock()

        job(ScriptedStore(), ObservabilityProperties(syncRunRetentionCron = "0 0 4 * * *"), scheduler).schedule()

        val trigger = argumentCaptor<CronTrigger>()
        verify(scheduler).schedule(any(), trigger.capture())
        assertThat(trigger.firstValue.expression).isEqualTo("0 0 4 * * *")
    }

    @Test
    fun `a failed prune is reported rather than thrown into the scheduler`() {
        val scheduler: TaskScheduler = mock()
        val store = ScriptedStore().apply { failure = IllegalStateException("Neo4j is not reachable") }
        job(store, scheduler = scheduler).schedule()

        val task = argumentCaptor<Runnable>()
        verify(scheduler).schedule(task.capture(), any<CronTrigger>())

        // Tomorrow night's run is the retry; an exception here would only reach a generic handler.
        assertThatCode { task.firstValue.run() }.doesNotThrowAnyException()
    }
}
