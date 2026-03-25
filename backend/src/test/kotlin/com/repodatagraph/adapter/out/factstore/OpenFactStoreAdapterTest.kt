package com.repodatagraph.adapter.out.factstore

import com.repodatagraph.domain.model.AuditEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class OpenFactStoreAdapterTest {

    private val adapter = OpenFactStoreAdapter(
        baseUrl = "http://localhost:9999",
        orgSlug = "test-org",
        apiKey = "test-key"
    )

    @Test
    fun `recordEvent does not throw when factstore is unavailable`() {
        val event = AuditEvent(
            id = "1",
            eventType = "REPOSITORY_REGISTERED",
            repoId = "repo1",
            actor = "system",
            timestamp = Instant.now()
        )
        assertDoesNotThrow { adapter.recordEvent(event) }
    }

    @Test
    fun `queryEvents returns empty list when factstore is unavailable`() {
        val result = adapter.queryEvents("repo1")
        assertTrue(result.isEmpty())
    }
}
