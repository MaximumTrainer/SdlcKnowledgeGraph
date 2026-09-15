package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.domain.port.out.connector.SyncRequest
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
 */
@Service
class SyncService(
    private val registry: AdapterRegistry,
    private val writer: GraphDeltaWriter,
    private val recorder: SyncRunRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Connectors with a run in flight. In-process because the scheduler is in-process. */
    private val running = ConcurrentHashMap.newKeySet<String>()

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
        ) : PageStep
    }

    /** Does the work of a run started by [start]. Separated so the API can answer 202 straight away. */
    fun execute(
        name: String,
        runId: String,
        mode: SyncMode,
    ) {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)

        val outcome =
            try {
                collectPages(registered, runId, mode)
            } catch (
                // A connector is somebody else's code reaching somebody else's API, so anything can
                // come out of it. The run has to record that rather than let it escape, or a failure
                // leaves a node saying RUNNING for ever with nothing to say why.
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                log.error("connector {} run {} failed", name, runId, failure)
                RunOutcome(RunStatus.FAILED, error = failure.message)
            } finally {
                running.remove(name)
            }

        recorder.recordRun(runId, registered, mode, outcome.status, outcome.totals, outcome.watermark, outcome.error)
        if (outcome.status == RunStatus.SUCCESS) {
            recorder.recordState(name, runId, outcome.status, outcome.watermark)
        }
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
        val since = if (mode == SyncMode.FULL) null else recorder.watermarkFor(registered.name)
        val pages = registered.connector.sync(SyncRequest(since = since, mode = mode)).iterator()

        var totals = DeltaResult()
        var watermark: Instant? = null
        var partial = false
        var error: String? = null
        var finished = false

        while (!finished) {
            when (val step = nextPage(pages, registered.name, runId)) {
                is PageStep.Done -> finished = true
                is PageStep.Failed -> {
                    partial = true
                    error = step.message
                    finished = true
                }
                is PageStep.Next -> {
                    val applied = applyPage(step.page, registered.name, runId, registered)
                    if (applied == null) partial = true else totals += applied
                    step.page.watermark?.let { watermark = it }
                }
            }
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
            log.warn("connector {} failed on a page; run {} is partial", name, runId, failure)
            PageStep.Failed(failure.message)
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
                log.info("delivery {} for {} was already applied by run {}", id, name, existing)
                return existing
            }
        }

        val delta = registered.connector.onWebhook(event) ?: return null
        val runId = newRunId()
        recorder.recordRun(runId, registered, SyncMode.WEBHOOK, RunStatus.RUNNING, DeltaResult(), null, null, deliveryId)

        val totals =
            try {
                writer.apply(delta, registered.descriptor, runId)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                log.error("webhook for {} failed to apply in run {}", name, runId, failure)
                recorder.recordRun(
                    runId,
                    registered,
                    SyncMode.WEBHOOK,
                    RunStatus.FAILED,
                    DeltaResult(),
                    null,
                    failure.message,
                    deliveryId,
                )
                return runId
            }

        recorder.recordRun(runId, registered, SyncMode.WEBHOOK, RunStatus.SUCCESS, totals, delta.watermark, null, deliveryId)
        return runId
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
            log.warn("connector {} produced a page that could not be applied in run {}", name, runId, failure)
            null
        }

    private fun requireCapability(
        name: String,
        capabilities: Set<Capability>,
        mode: SyncMode,
    ) {
        val needed =
            when (mode) {
                SyncMode.FULL -> Capability.FULL
                SyncMode.INCREMENTAL -> Capability.INCREMENTAL
                SyncMode.WEBHOOK -> Capability.WEBHOOK
            }
        if (needed !in capabilities) throw UnsupportedCapabilityException(name, needed)
    }

    private fun newRunId(): String = UUID.randomUUID().toString()
}
