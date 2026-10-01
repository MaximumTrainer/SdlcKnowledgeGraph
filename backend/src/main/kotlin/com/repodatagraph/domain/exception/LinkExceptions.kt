package com.repodatagraph.domain.exception

import com.repodatagraph.domain.model.CandidateStatus

/** No current candidate link has this id (#28). Answered 404. */
class CandidateNotFoundException(
    val id: String,
) : RuntimeException("no candidate link '$id'")

/** The candidate was already decided, and a decision is not taken back by another (#28). Answered 409. */
class CandidateDecidedException(
    val id: String,
    val status: CandidateStatus,
) : RuntimeException("candidate link '$id' is already ${status.wireName}")

/** A manual link between these two already stands (#28). Answered 409. */
class ManualLinkExistsException(
    val resourceKey: String,
    val repoKey: String,
) : RuntimeException("$repoKey is already stated to own $resourceKey")

/** No current manual link between these two to close (#28). Answered 404. */
class ManualLinkNotFoundException(
    val resourceKey: String,
    val repoKey: String,
) : RuntimeException("no manual link from $repoKey to $resourceKey")
