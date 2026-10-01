package com.repodatagraph.application.links

import com.repodatagraph.application.connector.RunStatus
import com.repodatagraph.application.connector.SyncRunCompleted
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.TouchedKeys
import com.repodatagraph.domain.port.`in`.LinkUseCase
import com.repodatagraph.domain.port.out.LinkQueries
import com.repodatagraph.observability.LogEvents

/**
 * Follows a connector's run with a resolution of what it touched (#28, FR7), so new evidence - a
 * tag, an IaC file, a deployment - is linked without waiting for the nightly full pass.
 *
 * Only for [connectors] whose facts are evidence, and only after a run that wrote something: a
 * failed run wrote nothing to follow, and a partial one still wrote its pages.
 */
class LinkResolutionTrigger(
    private val links: LinkUseCase,
    private val queries: LinkQueries,
    private val connectors: Set<String>,
) {
    fun onSyncRunCompleted(event: SyncRunCompleted) {
        val wrote = event.status == RunStatus.SUCCESS || event.status == RunStatus.PARTIAL
        val touched = if (event.connector in connectors && wrote) queries.touchedBy(event.runId) else TouchedKeys()
        if (touched.empty) return
        val started = links.resolve(LinkScope(resourceKeys = touched.resources, repoKeys = touched.repositories))
        LogEvents.linksResolutionTriggered(event.connector, event.runId, started.syncRunId)
    }
}
