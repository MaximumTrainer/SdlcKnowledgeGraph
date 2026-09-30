package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException

/**
 * What a change-impact question asks (#87): which repository is about to change, optionally which
 * paths in it and at which commit, how far to walk and how many hits to return. Bounded here (FR1),
 * so no caller can ask for an unbounded answer, and a value out of bounds is refused naming the field
 * and the bound before anything is walked.
 */
data class ChangeImpactQuery(
    val repositoryKey: String,
    val paths: List<String> = emptyList(),
    val sha: String? = null,
    val depth: Int = DEFAULT_DEPTH,
    val limit: Int = DEFAULT_LIMIT,
    /** Who assigns [providerId]; github when not named (#88). */
    val provider: String? = null,
    /** The provider's id for the repository, consulted before [repositoryKey] (#88, FR5). */
    val providerId: String? = null,
) {
    init {
        if (repositoryKey.isBlank() && providerId.isNullOrBlank()) {
            throw InvalidQueryParameterException("repositoryKey", "repositoryKey or providerId is required")
        }
        if (depth !in MIN_DEPTH..MAX_DEPTH) {
            throw InvalidQueryParameterException("depth", "depth must be between $MIN_DEPTH and $MAX_DEPTH, was $depth")
        }
        if (limit !in MIN_LIMIT..MAX_LIMIT) {
            throw InvalidQueryParameterException("limit", "limit must be between $MIN_LIMIT and $MAX_LIMIT, was $limit")
        }
        if (paths.size > MAX_PATHS) {
            throw InvalidQueryParameterException("paths", "paths holds at most $MAX_PATHS entries, was ${paths.size}")
        }
        if (paths.any { it.isBlank() || it.length > MAX_PATH_LENGTH }) {
            throw InvalidQueryParameterException("paths", "each path must be non-blank and at most $MAX_PATH_LENGTH characters")
        }
        if (sha != null && !SHA.matches(sha)) {
            throw InvalidQueryParameterException("sha", "sha must be a commit hash of 4 to 64 hexadecimal characters")
        }
    }

    /** The repository asked about by key, as the node key a Repository is stored under; null when asked by provider id only. */
    val repository: NodeKey? get() = repositoryKey.trim().takeIf { it.isNotEmpty() }?.let { NodeKey(REPOSITORY, it) }

    /** The repository's alias when a provider id was given (#88): the provider in lower case, github by default. */
    val alias: Map<String, String>?
        get() {
            val id = providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val by = provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROVIDER
            return mapOf("provider" to by, "providerId" to id)
        }

    companion object {
        const val DEFAULT_DEPTH = 2
        const val MIN_DEPTH = 1
        const val MAX_DEPTH = 4
        const val DEFAULT_LIMIT = 50
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 500
        const val MAX_PATHS = 100
        const val MAX_PATH_LENGTH = 1024
        private const val REPOSITORY = "Repository"
        private const val DEFAULT_PROVIDER = "github"
        private val SHA = Regex("[0-9a-fA-F]{4,64}")

        /** Reads a query from a request body as given, each absent field taking its default. */
        fun of(
            repositoryKey: String?,
            paths: List<String?>?,
            sha: String?,
            depth: Int?,
            limit: Int?,
            provider: String? = null,
            providerId: String? = null,
        ): ChangeImpactQuery {
            if (paths != null && paths.any { it == null }) throw InvalidQueryParameterException("paths", "paths must not hold null")
            return ChangeImpactQuery(
                repositoryKey = repositoryKey?.trim().orEmpty(),
                paths = paths.orEmpty().filterNotNull(),
                sha = sha,
                depth = depth ?: DEFAULT_DEPTH,
                limit = limit ?: DEFAULT_LIMIT,
                provider = provider,
                providerId = providerId,
            )
        }
    }
}

/**
 * How critical an environment is (#87, FR7), `Environment.tier` in the registry. Declared most
 * critical first. A tier that is missing - every environment written before the property existed -
 * or that is not one of these reads as [OTHER]: there is no migration mechanism yet (#33), and
 * guessing `production` from a name would be claiming something nobody stated.
 */
enum class EnvironmentTier(
    val wire: String,
) {
    PRODUCTION("production"),
    PRE_PRODUCTION("pre_production"),
    OTHER("other"),
    DEVELOPMENT("development"),
    ;

    companion object {
        fun of(value: Any?): EnvironmentTier {
            val text = value?.toString()?.trim()?.lowercase()
            return entries.firstOrNull { it.wire == text } ?: OTHER
        }
    }
}

/**
 * Scoring version 1 (#87, FR4), the formula a consumer is told the version of so it can notice a
 * change: `min(1, 1 / (1 + hops) * tierWeight * pathBoost)`. A hit that is not placed in any
 * environment is weighted as [EnvironmentTier.OTHER]; `pathBoost` is [PATH_MATCH_BOOST] when a
 * requested path names an index entry that names the hit, and 1 otherwise.
 */
object ImpactScoring {
    const val VERSION = "1"
    const val PATH_MATCH_BOOST = 2.0
    const val FORMULA = "min(1, 1 / (1 + hops) * tierWeight * pathBoost)"

    private const val PRODUCTION_WEIGHT = 1.0
    private const val PRE_PRODUCTION_WEIGHT = 0.6

    /** Unknown criticality, and no environment at all: above a known development, below pre-production. */
    private const val OTHER_WEIGHT = 0.5
    private const val DEVELOPMENT_WEIGHT = 0.3

    val TIER_WEIGHTS: Map<EnvironmentTier, Double> =
        mapOf(
            EnvironmentTier.PRODUCTION to PRODUCTION_WEIGHT,
            EnvironmentTier.PRE_PRODUCTION to PRE_PRODUCTION_WEIGHT,
            EnvironmentTier.OTHER to OTHER_WEIGHT,
            EnvironmentTier.DEVELOPMENT to DEVELOPMENT_WEIGHT,
        )
}

/** Whether the `paths` of a query could be evaluated (#87, FR2). Never silently "applied". */
enum class PathFilter(
    val wire: String,
) {
    /** No paths were asked about. */
    NOT_REQUESTED("not_requested"),

    /** Paths were asked about, but the repository has no manifest or IaC index to read them against. */
    NOT_APPLIED("not_applied"),

    /** The paths were read against the repository's index; matched ones boost what they name. */
    APPLIED("applied"),
}

/**
 * Whether a `sha` narrowed the answer (#87, FR3). A sha names Change nodes (#85); deployments whose
 * artifact CONTAINS none of them are left out, with whatever is reached only through them.
 */
enum class ChangeScope(
    val wire: String,
) {
    /** No sha was asked about. */
    NOT_REQUESTED("not_requested"),

    /** A sha was asked about, but no Change in the repository matches it, so it could not scope anything. */
    UNKNOWN("unknown"),

    /** The sha matched a Change: only deployments whose artifact contains it are in the answer. */
    APPLIED("applied"),
}

/** What kind of file an index entry is. */
enum class PathIndexKind {
    /** A dependency manifest, recorded as the `manifest` of the DEPENDS_ON edges read from it. */
    MANIFEST,

    /** An infrastructure-as-code file, an IacFile the repository CONTAINS_IAC. */
    IAC,
}

/**
 * One file the graph knows a repository holds, and what that file names: the resources an IaC file
 * references literally, or the keys of what a manifest's dependencies point at.
 */
data class PathIndexEntry(
    val path: String,
    val kind: PathIndexKind,
    val names: List<String>,
)

/** The facts a hit rests on (#87): the node, the path that reached it and where the node came from. */
data class Citation(
    val nodeKey: String,
    val edgePath: List<PathStep>,
    val provenance: Provenance,
)

/** One thing a change reaches, how far away, how much it matters, and who owns it. */
data class ImpactHit(
    val node: GraphNode,
    val hops: Int,
    val score: Double,
    /** The product of the provenance confidence of the edges on the path. */
    val confidence: Double,
    val inferred: Boolean,
    /** The environment the hit runs in, when it runs in one: itself for an Environment. */
    val environment: GraphNode?,
    val tier: EnvironmentTier,
    val pathMatched: Boolean,
    val owners: List<Owner>,
    val citation: Citation,
)

/** The ranked answer (#87), at most `limit` hits; `truncated` says more were found, never how many (FR6). */
data class ChangeImpactResult(
    val repository: GraphNode,
    val depth: Int,
    val limit: Int,
    val pathFilter: PathFilter,
    val matchedPaths: List<String>,
    val changeScope: ChangeScope,
    val truncated: Boolean,
    val hits: List<ImpactHit>,
)
