package com.repodatagraph.application.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.application.PropertyValidator
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.`in`.SeedIngestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader

/**
 * The seed endpoint decides in the same order as the deployment ingest endpoint (#7, FR5): whether it
 * is on, whether the caller may use it, whether the batch makes sense, and only then what it changes.
 */
class SeedIngestServiceTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val syncService: SyncService = mock()
    private val recorder: SyncRunRecorder = mock()

    private fun service(token: String = "the-token") =
        SeedIngestService(
            IngestProperties(token),
            SeedBatchParser(
                objectMapper,
                YamlOntologyLoader(DefaultResourceLoader()).load(),
                PropertyValidator(),
                IdentityResolver(),
                DerivedProperties(GitRemoteParser()),
            ),
            syncService,
            recorder,
        )

    private val batch =
        objectMapper.writeValueAsBytes(
            mapOf(
                "nodes" to listOf(mapOf("type" to "Team", "props" to mapOf("name" to "platform"))),
                "edges" to emptyList<Any>(),
            ),
        )

    @Test
    fun `is off when no token is configured, whatever is sent`() {
        assertThat(service(token = "").seed("Bearer anything", batch)).isEqualTo(SeedIngestOutcome.Disabled)
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `refuses a caller without the token before reading what they sent`() {
        assertThat(service().seed(null, "not even json".toByteArray())).isEqualTo(SeedIngestOutcome.Unauthorized)
        assertThat(service().seed("Bearer wrong", batch)).isEqualTo(SeedIngestOutcome.Unauthorized)
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `names what is wrong with a batch and applies none of it`() {
        val outcome = service().seed("Bearer the-token", "{}".toByteArray())

        assertThat((outcome as SeedIngestOutcome.Invalid).errors).containsKey("nodes")
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `applies a new batch through the dogfood-seed connector`() {
        whenever(syncService.applyWebhook(eq("dogfood-seed"), any())).thenReturn("run-1")

        val outcome = service().seed("Bearer the-token", batch) as SeedIngestOutcome.Accepted

        assertThat(outcome).isEqualTo(SeedIngestOutcome.Accepted(created = true, nodes = 1, edges = 0))
        verify(syncService).applyWebhook(eq("dogfood-seed"), any())
    }

    @Test
    fun `says a batch it has already applied created nothing`() {
        whenever(recorder.runForDelivery(eq("dogfood-seed"), anyOrNull())).thenReturn("run-1")

        val outcome = service().seed("Bearer the-token", batch) as SeedIngestOutcome.Accepted

        assertThat(outcome.created).isFalse()
    }
}
