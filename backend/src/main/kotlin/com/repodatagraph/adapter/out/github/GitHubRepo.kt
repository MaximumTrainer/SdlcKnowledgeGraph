package com.repodatagraph.adapter.out.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.time.Instant

/**
 * A repository as GitHub describes it, in GitHub's vocabulary.
 *
 * Only the fields this connector actually reads. A wider model would suggest the graph knows things
 * it does not, and `@JsonIgnoreProperties` means GitHub adding a field is not a failure - it is an
 * API that grows constantly, and a connector that broke on every addition would be unusable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GitHubRepo(
    val nodeId: String? = null,
    val name: String,
    val fullName: String,
    val htmlUrl: String,
    val defaultBranch: String? = null,
    val description: String? = null,
    val language: String? = null,
    val archived: Boolean = false,
    val visibility: String? = null,
    /** When GitHub last saw a push. Null for a repository nobody has ever pushed to. */
    val pushedAt: Instant? = null,
    val topics: List<String> = emptyList(),
)

/** The contents API's envelope: the file arrives base64-encoded inside JSON, not as itself. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubContent(
    val content: String? = null,
    val encoding: String? = null,
)

/**
 * A branch's whole file listing, in one response.
 *
 * `truncated` is GitHub saying the repository has more files than it will list at once. It is not an
 * error and it is not rare in a monorepo, so what is read is "every file GitHub was willing to name"
 * rather than "every file" - and the connector says so rather than quietly implying completeness.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubTree(
    val sha: String? = null,
    val truncated: Boolean = false,
    val tree: List<GitHubTreeEntry> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubTreeEntry(
    val path: String,
    /** `blob` for a file, `tree` for a directory, `commit` for a submodule. */
    val type: String,
    val size: Long = 0,
    val sha: String? = null,
)
