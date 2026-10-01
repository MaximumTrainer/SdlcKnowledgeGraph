package com.repodatagraph.domain.policy

import com.repodatagraph.domain.port.`in`.ResourceOwners
import com.repodatagraph.domain.port.out.PolicyDecisionPoint

/**
 * The policy's answer, asked again with the resource's owners when owning it could change the answer
 * (#30 FR7): only when the subject's roles refused it, the subject belongs to a team, and the resource
 * is one node. Every other question reads nothing from the graph to be decided. The gate and
 * `POST /api/v1/policy/explain` both decide through here, so the explanation is the gate's answer.
 */
fun PolicyDecisionPoint.decideWithOwnership(
    request: AuthzRequest,
    owners: ResourceOwners?,
): Decision {
    val decision = decide(request)
    val resource = request.resource
    val refusedByRole = !decision.allow && decision.policy == ROLES_POLICY
    val oneNode = resource.kind == ResourceKind.NODE && resource.type != null && resource.key != null
    val canOwn = owners != null && request.subject.teams.isNotEmpty()
    if (!(refusedByRole && oneNode && canOwn)) return decision
    val ownerTeams = owners!!.ownerTeams(resource.type!!, resource.key!!)
    return if (ownerTeams.isEmpty()) decision else decide(request.copy(resource = resource.copy(ownerTeams = ownerTeams)))
}

private const val ROLES_POLICY = "roles"
