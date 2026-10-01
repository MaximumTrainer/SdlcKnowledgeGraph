package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.PartialReadException
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.domain.port.out.connector.SyncRequest
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import com.repodatagraph.observability.LogEvents
import com.repodatagraph.observability.SyncErrorKind
import com.repodatagraph.observability.SyncMetrics
import com.repodatagraph.observability.WebhookResult
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A connector was asked for something it does not claim it can do. */
class UnsupportedCapabilityException(
    val connector: String,
    val capability: Capability,
) : IllegalArgumentException("connector '$connector' does not support $capability")

/** A run for this connector is already going. */
class SyncInProgressException(
    val connector: String,
) : IllegalStateException("a sync run for '$connector' is already in progress")

class UnknownConnectorException(
    val connector: String,
) : NoSuchElementException("no connector named '$connector'")

enum class RunStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED,
}

/**
 * Runs a connector and records what happened.
 *
 * The failure handling is the design. A sync reads an estate through somebody else's API, so a page
 * failing halfway is ordinary rather than exceptional - and losing the pages that worked because a
 * later one did not is both wasteful and misleading. So each page is applied on its own: a page that
 * throws marks the run PARTIAL and the run carries on, while a failure outside a page marks it
 * FAILED. A run's status therefore says what really happened rather than just whether an exception
 * escaped.
 *
 * A watermark is only advanced on a wholly successful run. Advancing it after a partial one would
 * silently skip whatever the failed page contained, and nothing would ever notice.
 *
 * Every run is reported to [SyncMetrics] and logged as `sync.started` and `sync.finished` (#29), and
 * both happen before the run's final status is written: whoever sees a run finished in the graph can
 * already see it in the meters, rather than racing them.
 */
@Service
class SyncService(
    private val registry: AdapterRegistry,
    private val writer: GraphDeltaWriter,
    private val recorder: SyncRunRecorder,
    private val metrics: SyncMetrics,
    private val clock: Clock,
) {
    /** Connectors with a run in flight. In-process because the scheduler is in-process. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    init {
        // Every connector the registry knows, so their series exist before their first run. The
        // gauges read the running set and the graph, so they cannot disagree with either.
        registry.all().forEach { registered ->
            val name = registered.name
            metrics.register(
                connector = name,
                sourceSystem = registered.descriptor.sourceSystem,
                modes = SyncMode.entries.filter { it.capability in registered.descriptor.capabilities }.toSet(),
                running = { isRunning(name) },
                lastSuccess = { recorder.lastSuccessAt(name) },
            )
        }
    }

    fun isRunning(connector: String): Boolean = connector in running

    /**
     * Starts a run and returns its id immediately.
     *
     * @throws SyncInProgressException if one is already going, rather than queueing: two runs of one
     *   connector produce interleaved writes nobody can reason about afterwards.
     */
    fun start(
        name: String,
        mode: SyncMode,
    ): String {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)
        requireCapability(registered.descriptor.name, registered.descriptor.capabilities, mode)
        if (!running.add(name)) throw SyncInProgressException(name)

        val runId = newRunId()
        recorder.recordRun(runId, registered, mode, RunStatus.RUNNING, DeltaResult(), null, null)
        return runId
    }

    /** How a run ended, before it is written down. */
    private data class RunOutcome(
        val status: RunStatus,
        val totals: DeltaResult = DeltaResult(),
        val watermark: Instant? = null,
        val error: String? = null,
    )

    /** What pulling the next page produced. */
    private sealed interface PageStep {
        data class Next(
            val page: GraphDelta,
        ) : PageStep

        data object Done : PageStep

        data class Failed(
            val message: String?,
            /** Each item the connector named as unreadable, or 1 when it named none. */
            val items: Int = 1,
        ) : PageStep
    }

    /** Does the work of a run started by [start]. Separated so the API can answer 202 straight away. */
    fun execute(
        name: String,
        runId: String,
        mode: SyncMode,
    ) {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)
        val startedAt = Instant.now(clock)
        LogEvents.syncStarted(name, runId, mode.name)

        val outcome =
            try {
                collectPages(registered, runId, mode)
            } catch (
                // A connector is somebody else's code reaching somebody else's API, so anything can
                // come out of it. The run has to record that rather than let it escape, or a failure
                // leaves a node saying RUNNING for ever with nothing to say why.
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                LogEvents.connectorRunFailed(name, runId, failure)
                metrics.error(name, SyncErrorKind.RUN)
                RunOutcome(RunStatus.FAILED, totals = DeltaResult(failed = 1), error = failure.message)
            } finally {
                running.remove(name)
            }

        report(name, runId, mode, outcome, startedAt)
        // Freshness counts from here, as ConnectorState does; a webhook run does not reset it,
        // because one event says nothing about whether the rest of the estate is current.
        if (outcome.status == RunStatus.SUCCESS) metrics.succeeded(name, Instant.now(clock))
        recorder.recordRun(runId, registered, mode, outcome.status, outcome.totals, outcome.watermark, outcome.error)
        // Whatever the status, so the state can count failures since the last success; the
        // recorder only moves the watermark and the last success on a success (#29, FR3).
        recorder.recordState(name, runId, outcome.status, outcome.watermark)
    }

    /**
     * Pulls and applies every page, keeping what worked.
     *
     * A page failing is ordinary - it is a read of somebody else's estate - so it downgrades the run
     * to PARTIAL rather than throwing away the pages that succeeded. The loop has one exit for the
     * same reason it has one status: what happened should be readable afterwards.
     */
    private fun collectPages(
        registered: RegisteredConnector,
        runId: String,
        mode: SyncMode,
    ): RunOutcome {
        // Before the first page is asked for, so every node this run writes is stamped at or after it.
        val startedAt = Instant.now(clock)
        val since = if (mode == SyncMode.FULL) null else recorder.watermarkFor(registered.name)
        val pages = registered.connector.sync(SyncRequest(since = since, mode = mode)).iterator()

        var totals = DeltaResult()
        var watermark: Instant? = null
        var partial = false
        var error: String? = null
        var finished = false
        var pageIndex = 0

        while (!finished) {
            when (val step = nextPage(pages, registered.name, runId)) {
                is PageStep.Done -> finished = true
                is PageStep.Failed -> {
                    partial = true
                    error = step.message
                    totals += DeltaResult(failed = step.items)
                    finished = true
                }
                is PageStep.Next -> {
                    metrics.page(registered.name)
                    val applied = applyPage(step.page, registered.name, runId, registered)
                    pageIndex++
                    if (applied == null) {
                        partial = true
                        totals += DeltaResult(failed = 1)
                    } else {
                        LogEvents.syncPage(registered.name, runId, pageIndex, applied.nodesUpserted, applied.edgesUpserted)
                        totals += applied
                    }
                    step.page.watermark?.let { watermark = it }
                }
            }
        }

        // Only a complete full run that saw every page can tell "gone" from "not looked at" (#150). An
        // incremental or partial run that closed what it did not see would erase the estate it failed
        // to read.
        if (!partial && mode == SyncMode.FULL && registered.descriptor.fullSyncIsComplete) {
            totals += DeltaResult(tombstones = writer.reconcile(registered.descriptor, startedAt, registered.tombstoneRules))
        }

        return RunOutcome(
            status = if (partial) RunStatus.PARTIAL else RunStatus.SUCCESS,
            totals = totals,
            // Only advanced on a wholly successful run: moving it past a page that failed would skip
            // whatever that page contained, permanently and silently.
            watermark = if (partial) null else watermark,
            error = error,
        )
    }

    private fun nextPage(
        pages: Iterator<GraphDelta>,
        name: String,
        runId: String,
    ): PageStep =
        try {
            if (pages.hasNext()) PageStep.Next(pages.next()) else PageStep.Done
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            LogEvents.connectorPageFailed(name, runId, failure)
            metrics.error(name, SyncErrorKind.PAGE)
            PageStep.Failed(failure.message, (failure as? PartialReadException)?.failures?.size?.coerceAtLeast(1) ?: 1)
        }

    /**
     * Applies a verified webhook as a run of its own, so the fact it carries is traceable too.
     *
     * Three exits, deliberately: the connector decided the event meant nothing, the write failed, or
     * it worked. Collapsing them would lose the distinction a caller needs.
     */
    @Suppress("ReturnCount")
    fun applyWebhook(
        name: String,
        event: WebhookEvent,
    ): String? {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)
        requireCapability(name, registered.descriptor.capabilities, SyncMode.WEBHOOK)

        // Before the connector is asked to interpret anything: a provider redelivers when it is
        // unsure the first attempt landed, and interpreting it again costs the same requests to the
        // source system before arriving at the same answer.
        val deliveryId = registered.connector.deliveryId(event)
        deliveryId?.let { id ->
            recorder.runForDelivery(name, id)?.let { existing ->
                LogEvents.connectorDeliveryDuplicate(name, id, existing)
                metrics.webhook(name, WebhookResult.IGNORED)
                return existing
            }
        }

        val delta = registered.connector.onWebhook(event)
        if (delta == null) {
            metrics.webhook(name, WebhookResult.IGNORED)
            return null
        }
        metrics.webhook(name, WebhookResult.APPLIED)
        val runId = newRunId()
        val startedAt = Instant.now(clock)
        LogEvents.syncStarted(name, runId, SyncMode.WEBHOOK.name)
        recorder.recordRun(runId, registered, SyncMode.WEBHOOK, RunStatus.RUNNING, DeltaResult(), null, null, deliveryId)

        val totals =
            try {
                writer.apply(delta, registered.descriptor, runId)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                LogEvents.connectorWebhookFailed(name, runId, failure)
                metrics.error(name, SyncErrorKind.RUN)
                report(name, runId, SyncMode.WEBHOOK, RunOutcome(RunStatus.FAILED), startedAt)
                recorder.recordRun(
                    runId,
                    registered,
                    SyncMode.WEBHOOK,
                    RunStatus.FAILED,
                    DeltaResult(failed = 1),
                    null,
                    failure.message,
                    deliveryId,
                )
                return runId
            }

        report(name, runId, SyncMode.WEBHOOK, RunOutcome(RunStatus.SUCCESS, totals), startedAt)
        recorder.recordRun(runId, registered, SyncMode.WEBHOOK, RunStatus.SUCCESS, totals, delta.watermark, null, deliveryId)
        return runId
    }

    /** How a run ended, as meters and as the `sync.finished` event. */
    private fun report(
        name: String,
        runId: String,
        mode: SyncMode,
        outcome: RunOutcome,
        startedAt: Instant,
    ) {
        val took = Duration.between(startedAt, Instant.now(clock))
        val totals = outcome.totals
        metrics.runFinished(
            name,
            mode,
            outcome.status.name,
            took,
            totals.nodesUpserted,
            totals.edgesUpserted,
            totals.tombstones,
        )
        LogEvents.syncFinished(
            name,
            runId,
            mode.name,
            outcome.status.name,
            took.toMillis().toInt(),
            totals.nodesUpserted,
            totals.edgesUpserted,
            totals.tombstones,
        )
    }

    /** One page, in its own try, so one bad page does not take the run with it. */
    private fun applyPage(
        page: GraphDelta,
        name: String,
        runId: String,
        registered: RegisteredConnector,
    ): DeltaResult? =
        try {
            writer.apply(page, registered.descriptor, runId)
        } catch (
            // Writing a page can fail on one bad node in it. That is a partial run, not a crash.
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            LogEvents.connectorPageUnapplied(name, runId, failure)
            metrics.error(name, SyncErrorKind.PAGE)
            null
        }

    private fun requireCapability(
        name: String,
        capabilities: Set<Capability>,
        mode: SyncMode,
    ) {
        val needed = mode.capability
        if (needed !in capabilities) throw UnsupportedCapabilityException(name, needed)
    }

    private fun newRunId(): String = UUID.randomUUID().toString()
}

/** What a connector has to be able to do to run in this mode. */
private val SyncMode.capability: Capability
    get() =
        when (this) {
            SyncMode.FULL -> Capability.FULL
            SyncMode.INCREMENTAL -> Capability.INCREMENTAL
            SyncMode.WEBHOOK -> Capability.WEBHOOK
        }
