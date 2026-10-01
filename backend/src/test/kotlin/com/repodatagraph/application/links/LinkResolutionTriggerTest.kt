package com.repodatagraph.application.links

import com.repodatagraph.application.connector.RunStatus
import com.repodatagraph.application.connector.SyncRunCompleted
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.ResolutionStarted
import com.repodatagraph.domain.model.TouchedKeys
import com.repodatagraph.domain.port.`in`.LinkUseCase
import com.repodatagraph.domain.port.out.connector.SyncMode
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * When a resolution follows a sync (#28, FR7): after a cloud or GitHub run that wrote something, a
 * resolution scoped to the resources and repositories that run touched, so new evidence is linked
 * without waiting for the nightly pass.
 */
class LinkResolutionTriggerTest {
    private val links: LinkUseCase = mock()
    private val queries = FakeLinkQueries(InMemoryGraphStore())
    private val trigger = LinkResolutionTrigger(links, queries, setOf("github", "aws", "azure", "gcp"))

    init {
        whenever(links.resolve(any())).thenReturn(ResolutionStarted("links-1", SyncMode.INCREMENTAL))
    }

    @Test
    fun `a successful GitHub run resolves what it touched`() {
        queries.touched["run-1"] = TouchedKeys(repositories = setOf("github.com/acme/payments"))

        trigger.onSyncRunCompleted(SyncRunCompleted("github", "run-1", SyncMode.INCREMENTAL, RunStatus.SUCCESS))

        verify(links).resolve(LinkScope(repoKeys = setOf("github.com/acme/payments")))
    }

    @Test
    fun `a partial cloud run still resolves the resources it did write`() {
        queries.touched["run-2"] = TouchedKeys(resources = setOf("aws:arn:aws:s3:::logs"))

        trigger.onSyncRunCompleted(SyncRunCompleted("aws", "run-2", SyncMode.FULL, RunStatus.PARTIAL))

        verify(links).resolve(LinkScope(resourceKeys = setOf("aws:arn:aws:s3:::logs")))
    }

    @Test
    fun `a failed run, a connector not listed and a run that touched nothing resolve nothing`() {
        queries.touched["run-3"] = TouchedKeys(repositories = setOf("github.com/acme/payments"))
        queries.touched["run-4"] = TouchedKeys(repositories = setOf("github.com/acme/payments"))

        trigger.onSyncRunCompleted(SyncRunCompleted("github", "run-3", SyncMode.FULL, RunStatus.FAILED))
        trigger.onSyncRunCompleted(SyncRunCompleted("servicenow", "run-4", SyncMode.FULL, RunStatus.SUCCESS))
        trigger.onSyncRunCompleted(SyncRunCompleted("github", "run-5", SyncMode.FULL, RunStatus.SUCCESS))

        verify(links, never()).resolve(any())
    }
}
