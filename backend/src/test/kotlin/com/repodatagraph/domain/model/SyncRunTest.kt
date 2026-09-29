package com.repodatagraph.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * A recorded run, and the question a caller may ask of the history (#29, FR5). The query refuses what
 * cannot mean anything when it is built, so no store is ever asked for page -1 or a window that ends
 * before it starts.
 */
class SyncRunTest {
    private val started = Instant.parse("2026-09-29T09:00:00Z")

    private fun run(finishedAt: Instant?) =
        SyncRun(
            id = "run-1",
            connector = "fake",
            sourceSystem = "fake",
            mode = "FULL",
            status = if (finishedAt == null) "RUNNING" else "SUCCESS",
            startedAt = started,
            finishedAt = finishedAt,
            nodesUpserted = 0,
            edgesUpserted = 0,
            tombstones = 0,
            watermark = null,
            sourceId = null,
            error = null,
        )

    @Test
    fun `a finished run took from its start to its finish`() {
        assertThat(run(started.plusSeconds(90)).duration).isEqualTo(Duration.ofSeconds(90))
    }

    @Test
    fun `a run still going has no duration yet`() {
        assertThat(run(finishedAt = null).duration).isNull()
    }

    @Test
    fun `a run records no per-connector details yet`() {
        assertThat(run(started).details).isEmpty()
    }

    @Test
    fun `a query defaults to the first page of twenty`() {
        val query = SyncRunQuery()

        assertThat(query.page).isZero()
        assertThat(query.size).isEqualTo(20)
        assertThat(query.offset).isZero()
    }

    @Test
    fun `a later page starts after the pages before it`() {
        assertThat(SyncRunQuery(page = 3, size = 25).offset).isEqualTo(75L)
    }

    @Test
    fun `a page holds at least one run and at most a hundred`() {
        assertThat(SyncRunQuery(size = SyncRunQuery.MAX_SIZE).size).isEqualTo(100)
        assertThatThrownBy { SyncRunQuery(size = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SyncRunQuery(size = 101) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pages count from zero`() {
        assertThatThrownBy { SyncRunQuery(page = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a window must start before it ends`() {
        assertThatThrownBy { SyncRunQuery(startedFrom = started, startedBefore = started) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(SyncRunQuery(startedFrom = started, startedBefore = started.plusSeconds(1)).startedFrom).isEqualTo(started)
    }

    @Test
    fun `the page count covers a partial last page`() {
        assertThat(SyncRunPage(emptyList(), totalElements = 41).totalPages(20)).isEqualTo(3)
        assertThat(SyncRunPage(emptyList(), totalElements = 40).totalPages(20)).isEqualTo(2)
        assertThat(SyncRunPage(emptyList(), totalElements = 0).totalPages(20)).isZero()
    }
}
