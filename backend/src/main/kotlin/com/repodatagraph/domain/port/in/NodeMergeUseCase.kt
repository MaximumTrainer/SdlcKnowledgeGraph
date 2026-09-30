package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.identity.MergeOutcome

/**
 * Merging a node into another of its type found to be the same thing (#98, FR-4). Privileged: the
 * route needs graph:admin as well as graph:write, since a merge cannot be undone by another write.
 */
interface NodeMergeUseCase {
    /**
     * Merges the node of [type] at [key] into the node [into] names: a key of the same type, or a full
     * `Type:key` id. Either key may also be given as a full id.
     *
     * @throws com.repodatagraph.domain.exception.NodeTypeNotFoundException if the type is undeclared
     * @throws com.repodatagraph.domain.exception.ManagedNodeTypeException if the type is written through its own API
     * @throws com.repodatagraph.domain.exception.InvalidMergeException into itself, across types, or of the graph's own records
     * @throws com.repodatagraph.domain.exception.NodeNotFoundException if either node is missing
     * @throws com.repodatagraph.domain.exception.MergeIntoRetiredException if the node merged into is retired
     * @throws com.repodatagraph.domain.exception.NodeAlreadyMergedException if the node was merged already
     * @throws com.repodatagraph.domain.exception.MergeConflictException if the two disagree on their merge scope or alias
     */
    fun merge(
        type: String,
        key: String,
        into: String,
        dryRun: Boolean,
    ): MergeOutcome
}
