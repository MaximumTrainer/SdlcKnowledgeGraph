package com.repodatagraph.domain.model

data class Pipeline(
    val id: String,
    val name: String,
    val provider: String,
    val repoId: String,
    val lastRunStatus: String? = null
)
