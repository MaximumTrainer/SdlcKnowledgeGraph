package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.Repository

data class CreateRepositoryRequest(
    val orgRepo: String,
    val defaultBranch: String = "main",
    val topics: List<String> = emptyList(),
    val codeowners: List<String> = emptyList(),
    val serviceId: String? = null,
    val language: String? = null,
    val description: String? = null
)

data class RepositoryResponse(
    val id: String,
    val orgRepo: String,
    val defaultBranch: String,
    val topics: List<String>,
    val codeowners: List<String>,
    val serviceId: String?,
    val language: String?,
    val description: String?
) {
    companion object {
        fun from(repo: Repository) = RepositoryResponse(
            id = repo.id,
            orgRepo = repo.orgRepo,
            defaultBranch = repo.defaultBranch,
            topics = repo.topics,
            codeowners = repo.codeowners,
            serviceId = repo.serviceId,
            language = repo.language,
            description = repo.description
        )
    }
}

data class LinkRequest(val targetId: String)
