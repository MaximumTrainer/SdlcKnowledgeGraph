package com.repodatagraph.domain.model

data class Repository(
    val id: String,
    val orgRepo: String,
    val defaultBranch: String = "main",
    val topics: List<String> = emptyList(),
    val codeowners: List<String> = emptyList(),
    val serviceId: String? = null,
    val language: String? = null,
    val description: String? = null
)
