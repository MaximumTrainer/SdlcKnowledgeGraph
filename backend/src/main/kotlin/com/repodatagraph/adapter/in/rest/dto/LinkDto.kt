package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.LinkedRepository
import com.repodatagraph.domain.model.LinkedResource
import com.repodatagraph.domain.model.OwnerLink
import com.repodatagraph.domain.model.ResolutionStarted
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/** `POST /api/v1/links/resolve`: no body, or no scope, is every resource. */
data class ResolveRequest(
    val scope: ResolveScope? = null,
)

@Schema(description = "Which resources to resolve; every field narrows, and none is a FULL run")
data class ResolveScope(
    val provider: String? = null,
    val accountId: String? = null,
    val repoKey: String? = null,
    val resourceKeys: Set<String>? = null,
    val repoKeys: Set<String>? = null,
) {
    fun toScope() = LinkScope(provider, accountId, repoKey, resourceKeys.orEmpty(), repoKeys.orEmpty())
}

data class ResolutionResponse(
    val syncRunId: String,
    val mode: String,
) {
    companion object {
        fun from(started: ResolutionStarted) = ResolutionResponse(started.syncRunId, started.mode.name)
    }
}

data class LinkedResourceResponse(
    val key: String,
    val name: String?,
    val provider: String?,
    val accountId: String?,
) {
    companion object {
        fun from(resource: LinkedResource) = LinkedResourceResponse(resource.key, resource.name, resource.provider, resource.accountId)
    }
}

data class LinkedRepositoryResponse(
    val key: String,
    val name: String?,
) {
    companion object {
        fun from(repository: LinkedRepository) = LinkedRepositoryResponse(repository.key, repository.name)
    }
}

@Schema(description = "A link a rule proposed below the ownership threshold, or that conflicts with the owner")
data class CandidateResponse(
    val id: String,
    val resource: LinkedResourceResponse,
    val repository: LinkedRepositoryResponse,
    val confidence: Double,
    @field:Schema(description = "The rule whose evidence is strongest: tag, deployment, iac or naming")
    val rule: String,
    val evidence: Map<String, String>,
    @field:Schema(description = "pending, conflict, rejected, superseded or accepted")
    val status: String,
    val createdAt: Instant?,
    val rejectedBy: String?,
    val rejectedAt: Instant?,
) {
    companion object {
        fun from(candidate: CandidateLink) =
            CandidateResponse(
                id = candidate.id,
                resource = LinkedResourceResponse.from(candidate.resource),
                repository = LinkedRepositoryResponse.from(candidate.repository),
                confidence = candidate.confidence,
                rule = candidate.rule,
                evidence = candidate.evidence,
                status = candidate.status.wireName,
                createdAt = candidate.createdAt,
                rejectedBy = candidate.rejectedBy,
                rejectedAt = candidate.rejectedAt,
            )
    }
}

data class CandidatePageResponse(
    val items: List<CandidateResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(page: CandidatePage) =
            CandidatePageResponse(page.items.map(CandidateResponse::from), page.page, page.size, page.totalElements, page.totalPages)
    }
}

@Schema(description = "An OWNS_RESOURCE: who owns the resource, on which rule, and who said so")
data class OwnerLinkResponse(
    val resource: LinkedResourceResponse,
    val repository: LinkedRepositoryResponse,
    val rule: String,
    val confidence: Double,
    val inferred: Boolean,
    val evidence: Map<String, String>,
    val acceptedBy: String?,
    val sourceSystem: String,
) {
    companion object {
        fun from(owner: OwnerLink) =
            OwnerLinkResponse(
                LinkedResourceResponse.from(owner.resource),
                LinkedRepositoryResponse.from(owner.repository),
                owner.rule,
                owner.confidence,
                owner.inferred,
                owner.evidence,
                owner.acceptedBy,
                owner.sourceSystem,
            )
    }
}

/** `POST /api/v1/links/manual`: both ends, by key. */
data class ManualLinkRequest(
    val resourceKey: String? = null,
    val repoKey: String? = null,
)
