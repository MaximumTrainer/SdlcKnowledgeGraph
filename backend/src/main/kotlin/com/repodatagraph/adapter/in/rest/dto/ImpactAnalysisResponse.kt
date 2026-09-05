package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.Repository

data class ImpactAnalysisResponse(
    val repoId: String,
    val dependents: List<Repository>,
    val cloudResources: List<CloudResource>,
    val deployments: List<Deployment>,
)
