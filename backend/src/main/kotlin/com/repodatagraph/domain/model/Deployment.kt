package com.repodatagraph.domain.model

import java.time.Instant

data class Deployment(
    val id: String,
    val artifactId: String,
    val environmentId: String,
    val deployedAt: Instant = Instant.now(),
    val deployedBy: String? = null,
    val status: String = "SUCCESS"
)
