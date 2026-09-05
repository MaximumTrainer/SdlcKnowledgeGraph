package com.repodatagraph.domain.model

import java.time.Instant

data class AuditEvent(
    val id: String,
    val eventType: String,
    val repoId: String? = null,
    val artifactId: String? = null,
    val actor: String? = null,
    val timestamp: Instant = Instant.now(),
    val details: Map<String, String> = emptyMap(),
)
