package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.ImpactResult
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.OwnersResult
import com.repodatagraph.domain.model.WhyFailedResult

/** The multi-hop questions of #21, which [GraphQueryUseCase] answers alongside its one-hop ones. */
interface ImpactUseCase {
    /** What a change to a node reaches, with the paths that explain it (#21, FR1 to FR3). */
    fun impact(spec: ImpactSpec): ImpactResult

    /** Why a deployment failed, as far as the graph can tell (#21, FR4 and FR5). */
    fun whyDeploymentFailed(deploymentId: String): WhyFailedResult

    /** Who owns a node, directly or through what it inherits ownership from (#21, FR6). */
    fun owners(nodeId: String): OwnersResult
}
