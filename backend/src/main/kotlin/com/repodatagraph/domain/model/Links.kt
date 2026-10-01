package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.port.out.connector.SyncMode
import java.time.Instant

/**
 * Which resources a link resolution looks at (#28, FR6). Empty is every current cloud resource, a
 * FULL run; anything named narrows it, an INCREMENTAL run.
 *
 * [provider] and [accountId] narrow by the resource itself. [repoKey] and [repoKeys] take the
 * resources a repository is, or was, linked or proposed to. [resourceKeys] names resources outright.
 * Between the resource keys and the repository keys, a resource either names is resolved; that is
 * the scope a connector's run leaves behind (FR7).
 */
data class LinkScope(
    val provider: String? = null,
    val accountId: String? = null,
    val repoKey: String? = null,
    val resourceKeys: Set<String> = emptySet(),
    val repoKeys: Set<String> = emptySet(),
) {
    val full: Boolean
        get() = provider == null && accountId == null && repoKey == null && resourceKeys.isEmpty() && repoKeys.isEmpty()

    val mode: SyncMode get() = if (full) SyncMode.FULL else SyncMode.INCREMENTAL

    /** The repositories named, either way. */
    val repositories: Set<String> get() = repoKeys + listOfNotNull(repoKey)
}

/** A resolution has been accepted and will run as [syncRunId]. */
data class ResolutionStarted(
    val syncRunId: String,
    val mode: SyncMode,
)

/**
 * Where a candidate link stands (#28, FR5). Pending and conflict are open for review; rejected and
 * superseded are tombstones the engine does not propose again while the evidence is the same; an
 * accepted one has become a manual OWNS_RESOURCE and is closed.
 */
enum class CandidateStatus(
    val wireName: String,
) {
    PENDING("pending"),
    CONFLICT("conflict"),
    REJECTED("rejected"),
    SUPERSEDED("superseded"),
    ACCEPTED("accepted"),
    ;

    val open: Boolean get() = this in OPEN

    /** A decision that holds while the evidence it was made on holds. */
    val tombstone: Boolean get() = this == REJECTED || this == SUPERSEDED

    companion object {
        val OPEN: Set<CandidateStatus> = setOf(PENDING, CONFLICT)

        fun fromWireName(name: String): CandidateStatus =
            entries.firstOrNull { it.wireName == name.lowercase() }
                ?: throw InvalidQueryParameterException("status", "unknown candidate status '$name'")
    }
}

/** The cloud end of a link, as the review page shows it. */
data class LinkedResource(
    val key: String,
    val name: String?,
    val provider: String?,
    val accountId: String?,
)

/** The repository end of a link, as the review page shows it. */
data class LinkedRepository(
    val key: String,
    val name: String?,
)

/** A CANDIDATE_LINK as a reviewer reads it: who might own what, on which evidence, and how sure. */
data class CandidateLink(
    val id: String,
    val resource: LinkedResource,
    val repository: LinkedRepository,
    val confidence: Double,
    val rule: String,
    val evidence: Map<String, String>,
    val status: CandidateStatus,
    val createdAt: Instant?,
    val rejectedBy: String? = null,
    val rejectedAt: Instant? = null,
)

/** A page of the review list, open candidates by default, strongest first. */
data class CandidateQuery(
    val statuses: Set<CandidateStatus> = CandidateStatus.OPEN,
    val provider: String? = null,
    val minConfidence: Double? = null,
    val search: String? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        if (statuses.isEmpty()) throw InvalidQueryParameterException("status", "status names no candidate status")
        if (minConfidence != null && minConfidence !in 0.0..1.0) {
            throw InvalidQueryParameterException("minConfidence", "minConfidence must be between 0 and 1")
        }
        if (page < 0) throw InvalidQueryParameterException("page", "page must not be negative")
        if (size !in 1..MAX_SIZE) throw InvalidQueryParameterException("size", "size must be between 1 and $MAX_SIZE")
    }

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200
    }
}

data class CandidatePage(
    val items: List<CandidateLink>,
    val totalElements: Long,
    val page: Int = 0,
    val size: Int = CandidateQuery.DEFAULT_SIZE,
) {
    val totalPages: Int get() = ((totalElements + size - 1) / size).toInt()
}

/** An OWNS_RESOURCE as the link endpoints answer it: stated or inferred, on what, by whom. */
data class OwnerLink(
    val resource: LinkedResource,
    val repository: LinkedRepository,
    val rule: String,
    val confidence: Double,
    val inferred: Boolean,
    val evidence: Map<String, String> = emptyMap(),
    val acceptedBy: String? = null,
    val sourceSystem: String,
)

/**
 * A deployment that names a resource as its target, traced to the repository its artifact was built
 * from: the deployment rule's evidence (#28, FR2).
 */
data class DeploymentEvidence(
    val deploymentKey: String,
    val artifactKey: String,
    val repoKey: String,
    val environment: String?,
    val deployedAt: Instant?,
)

/** The resources and repositories a sync run wrote or named (#28, FR7). */
data class TouchedKeys(
    val resources: Set<String> = emptySet(),
    val repositories: Set<String> = emptySet(),
) {
    val empty: Boolean get() = resources.isEmpty() && repositories.isEmpty()
}
