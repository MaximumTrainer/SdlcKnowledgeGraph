package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.AuditEvent

interface FactStorePort {
    fun recordEvent(event: AuditEvent)

    fun queryEvents(repoId: String): List<AuditEvent>
}
