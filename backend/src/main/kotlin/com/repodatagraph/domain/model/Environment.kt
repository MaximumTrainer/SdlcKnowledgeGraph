package com.repodatagraph.domain.model

data class Environment(
    val id: String,
    val name: String,
    val type: String = "production",
)
