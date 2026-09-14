package com.repodatagraph.domain.identity

import com.repodatagraph.domain.exception.InvalidGitRemoteException
import org.springframework.stereotype.Component

/**
 * Parses any written form of a git remote into the parts that identify a Repository.
 *
 * A repository is named differently by everything that mentions it: a person pastes the browser URL,
 * git writes the scp-style remote, a CI file uses the bare `org/name` shorthand, a CMDB stores
 * something else again. Unless all of them reduce to one key, the graph grows duplicates that no
 * traversal can reconcile - which is the failure this whole class exists to prevent.
 *
 * The accepted and rejected forms are a table in `frontend/src/test/fixtures/git-remotes.json`,
 * shared with the TypeScript port the editing screen uses, so the key previewed before saving is the
 * key the API derives after.
 */
@Component
class GitRemoteParser {
    /**
     * @throws InvalidGitRemoteException when the input is not a git remote. The reason is carried
     *   separately from the message because the API reports it as a field a caller can act on.
     */
    fun parse(input: String): GitRemote {
        // A trailing slash survives a copy and paste and means nothing.
        val remote = input.trim().removeSuffix("/")

        // One refusal, with the reason worked out separately. Every way a string can fail to be a
        // remote ends here, so there is one place that decides and one place that explains.
        val parts =
            SCP_LIKE.matchEntire(remote)?.groupValues?.drop(1)
                ?: WITH_SCHEME.matchEntire(remote)?.groupValues?.drop(2)
                ?: HOST_AND_PATH.matchEntire(remote)?.groupValues?.drop(1)
                ?: SHORTHAND
                    .matchEntire(remote)
                    ?.groupValues
                    ?.drop(1)
                    ?.let { listOf(DEFAULT_HOST) + it }
                ?: throw InvalidGitRemoteException(rejectionReason(remote), input)

        val (host, org, name) = parts
        return GitRemote(
            host = host.lowercase(),
            org = org.lowercase(),
            // Only a trailing `.git` is a suffix; a dot inside the name belongs to it.
            name = name.removeSuffix(GIT_SUFFIX).lowercase(),
        )
    }

    /**
     * Why a string that looks URL-ish is still not a remote.
     *
     * Worth the extra work: "fewer than two path segments" tells someone who pasted a link to a file
     * what to paste instead, where "not a recognisable git remote" leaves them guessing.
     */
    private fun rejectionReason(remote: String): String {
        val scheme = SCHEME_PREFIX.find(remote)?.groupValues?.get(1)
        // The host is not a path segment. Counting it would tell someone who pasted a link to a page
        // that their URL points inside a repository, when it names no repository at all.
        val afterScheme = remote.substringAfter("://")
        val pathSegments = afterScheme.split("/").filter { it.isNotEmpty() }
        val segments = if (scheme != null) pathSegments.drop(1) else pathSegments
        return when {
            remote.isEmpty() -> "it is empty"
            remote.any { it.isWhitespace() } -> "it contains whitespace"
            scheme != null && scheme.lowercase() !in GIT_SCHEMES -> "'$scheme' is not a scheme git speaks"
            segments.size < MIN_SEGMENTS -> "it needs an organisation and a repository name"
            else -> "it points inside a repository rather than at one"
        }
    }

    private companion object {
        /** The host a bare `org/name` shorthand is assumed to be on. */
        const val DEFAULT_HOST = "github.com"
        const val GIT_SUFFIX = ".git"

        /** Two for the bare shorthand, three once a host is named. */
        const val MIN_SEGMENTS = 2

        val GIT_SCHEMES = setOf("https", "http", "ssh", "git")

        /** `git@github.com:acme/payments.git` - what git itself writes for an SSH remote. */
        val SCP_LIKE = Regex("""^[\w.\-]+@([\w.\-]+):([\w.\-]+)/([\w.\-]+)$""")

        /** `https://github.com/acme/payments` and `ssh://git@github.com/acme/payments.git`. */
        val WITH_SCHEME =
            Regex("""^(https|http|ssh|git)://(?:[\w.\-]+@)?([\w.\-]+)/([\w.\-]+)/([\w.\-]+)$""", RegexOption.IGNORE_CASE)

        /**
         * `github.com/acme/payments`, as written in prose. The host must carry a dot, which is what
         * separates it from a three-segment path that names no host at all.
         */
        val HOST_AND_PATH = Regex("""^([\w\-]+(?:\.[\w\-]+)+)/([\w.\-]+)/([\w.\-]+)$""")

        /** `acme/payments`, which assumes [DEFAULT_HOST]. */
        val SHORTHAND = Regex("""^([\w.\-]+)/([\w.\-]+)$""")

        val SCHEME_PREFIX = Regex("""^([a-zA-Z][\w+.\-]*)://""")
    }
}
