package com.repodatagraph.domain.port.`in`

/**
 * The teams that own a node (#30 FR7), for the policy's ownership rule: a member of an owning team
 * may curate it whatever their global role. A node is owned by the teams it is `OWNED_BY`, and a
 * resource a repository or service owns (`OWNS_RESOURCE`) by the teams that own that.
 */
fun interface ResourceOwners {
    /** The keys of the teams that own the node [type] [key]; empty when it has none, or no such node. */
    fun ownerTeams(
        type: String,
        key: String,
    ): Set<String>
}
