package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.model.SourceLag

/** Every source with something to lag, in declaration order (#93). */
data class SourceFreshnessResponse(
    val sources: List<SourceLagResponse>,
) {
    companion object {
        fun from(lag: List<SourceLag>) = SourceFreshnessResponse(lag.map(SourceLagResponse::from))
    }
}

/**
 * One source's lag. [window] is an ISO-8601 duration and [windowSeconds] the same in seconds, so a
 * caller can compare [lagSeconds] with it without parsing. [lastSuccessAt] and [lagSeconds] are null
 * for a source that has never synced successfully.
 */
data class SourceLagResponse(
    val source: String,
    val window: String,
    val windowSeconds: Long,
    val lastSuccessAt: String?,
    val lagSeconds: Long?,
    val lagging: Boolean,
) {
    companion object {
        fun from(lag: SourceLag) =
            SourceLagResponse(
                source = lag.source,
                window = lag.window.toString(),
                windowSeconds = lag.window.seconds,
                lastSuccessAt = lag.lastSuccessAt?.toString(),
                lagSeconds = lag.lag?.seconds,
                lagging = lag.lagging,
            )
    }
}

/**
 * The freshness policy as `GET /api/v1/ontology` publishes it (#93, FR-2): the default window and
 * every declared source's own, as ISO-8601 durations. Configuration rather than registry, so the
 * committed `ontology.json`, rendered from the registry alone, leaves it out.
 */
data class FreshnessPolicyResponse(
    val defaultWindow: String,
    val windows: Map<String, String>,
) {
    companion object {
        fun from(policy: FreshnessPolicy) =
            FreshnessPolicyResponse(
                defaultWindow = policy.defaultWindow.toString(),
                windows = policy.windows().mapValues { (_, window) -> window.toString() },
            )
    }
}
