package com.repodatagraph.domain.exception

import com.repodatagraph.domain.identity.MergeConflict
import com.repodatagraph.domain.model.NodeKey

/**
 * Two nodes disagree on something the registry says they must share to be one thing (#98): a
 * property in the type's merge scope, or part of its alias. Every such property is named with both
 * values, since which of them is wrong is the caller's to find out, not the merge's to pick.
 */
class MergeConflictException(
    val conflicts: List<MergeConflict>,
) : RuntimeException("identity conflict: ${conflicts.joinToString { it.field }}")

/** A merge that could never make sense, whatever the nodes hold: into itself, or across types (#98). */
class InvalidMergeException(
    val reason: String,
) : RuntimeException("invalid merge: $reason")

/**
 * The node merged into has been retired (#98). Its edges are closed, so moving more onto it would
 * hide them; bring it back first, or merge into the node that replaced it.
 */
class MergeIntoRetiredException(
    val into: NodeKey,
) : RuntimeException("merge into a retired node: ${into.id}")

/**
 * The node was merged into [mergedInto] already (#98). Writing it again would bring back the
 * duplicate a merge removed, and merging it again has nothing left to move.
 */
class NodeAlreadyMergedException(
    val node: NodeKey,
    val mergedInto: NodeKey,
) : RuntimeException("${node.id} was merged into ${mergedInto.id}")
