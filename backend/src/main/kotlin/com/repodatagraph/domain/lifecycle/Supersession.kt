package com.repodatagraph.domain.lifecycle

import com.repodatagraph.domain.model.NodeKey
import java.time.Instant

/** A fact a newer one replaced, and the instant it was replaced (#90): its validity ends [at] then. */
data class Supersession(
    val key: NodeKey,
    val at: Instant,
)

/**
 * What a deployment replaces: the same artifact by registry and name, whatever its digest or version.
 * An artifact with no registry is a family of its own, since nothing says which registry it was.
 */
data class ArtifactFamily(
    val registry: String?,
    val name: String,
)

/** A Deployment as supersession reads it: what it deployed, where, when, and whether it worked. */
data class DeploymentRecord(
    val key: NodeKey,
    val family: ArtifactFamily,
    val environmentKey: String,
    val deployedAt: Instant,
    val succeeded: Boolean,
)

/**
 * When one deployment ends another (#90, FR-4).
 *
 * A successful deployment of an artifact family to an environment ends the successful deployment of
 * that family there before it, at the instant it was deployed: what runs there now is the newer one.
 * A failed deployment replaced nothing, so it neither ends one nor is ended. Deployments at the same
 * instant cannot be ordered and end neither.
 *
 * Pure, and decided over everything at once, so the order the deployments are read in - a poll, a
 * webhook, one redelivered late - cannot change the answer: an older deployment arriving after a
 * newer one is ended at once by it. Only pairs this read touched are decided, so two current
 * deployments some other writer left are left to it.
 */
object DeploymentSupersession {
    /**
     * @param written the deployments a read is writing now
     * @param current the deployments the graph holds as current for the same families and environments
     */
    fun closures(
        written: List<DeploymentRecord>,
        current: List<DeploymentRecord>,
    ): List<Supersession> {
        val touched = written.map { it.key }.toSet()
        val all = (written + current.filterNot { it.key in touched }).distinctBy { it.key }.filter { it.succeeded }
        return all
            .groupBy { it.family to it.environmentKey }
            .values
            .flatMap { group ->
                group.mapNotNull { deployment ->
                    group
                        .filter { it.deployedAt.isAfter(deployment.deployedAt) }
                        .minByOrNull { it.deployedAt }
                        ?.takeIf { next -> deployment.key in touched || next.key in touched }
                        ?.let { next -> Supersession(deployment.key, next.deployedAt) }
                }
            }
    }
}
