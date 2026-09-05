package com.repodatagraph.domain.model

data class ServiceNowCI(
    val id: String,
    val ciName: String,
    val serviceId: String,
    val repoId: String? = null,
)
