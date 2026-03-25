package com.repodatagraph.domain.model

data class CloudResource(
    val id: String,
    val provider: String,
    val resourceType: String,
    val name: String,
    val region: String? = null,
    val repoId: String? = null
)
