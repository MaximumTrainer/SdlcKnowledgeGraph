package com.repodatagraph.domain.model

data class Artifact(
    val id: String,
    val name: String,
    val version: String,
    val commitSha: String? = null,
    val repoId: String? = null,
    val artifactType: String = "docker",
)
