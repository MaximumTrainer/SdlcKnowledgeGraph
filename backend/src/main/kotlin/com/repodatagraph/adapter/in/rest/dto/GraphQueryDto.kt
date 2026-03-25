package com.repodatagraph.adapter.`in`.rest.dto

data class ImpactAnalysisResponse(
    val repoId: String,
    val dependents: List<Any>,
    val cloudResources: List<Any>,
    val deployments: List<Any>
)
