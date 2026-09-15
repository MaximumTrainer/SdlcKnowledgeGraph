package com.repodatagraph.adapter.out.github

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Base64

/**
 * What this connector asks GitHub for: an org's repositories, a repository's file tree, and one file
 * out of it.
 *
 * Pagination is followed to the end here rather than left to the caller, because a caller that
 * forgot would ingest part of an estate and report success. Everything else about talking to GitHub
 * - authentication, retries, rate limits - belongs to [GitHubHttp].
 */
@Component
class GitHubClient(
    private val http: GitHubHttp,
    private val properties: GitHubProperties,
    private val codeownersParser: CodeownersParser = CodeownersParser(),
) {
    /**
     * Every repository in the org, page by page.
     *
     * A sequence rather than a list: an org with ten thousand repositories is ten thousand objects
     * held at once otherwise, and the caller writes each page as it arrives anyway.
     */
    fun repositories(org: String): Sequence<GitHubRepo> =
        sequence {
            var next: URI? = URI.create("${properties.baseUrl}/orgs/$org/repos?per_page=$PAGE_SIZE&sort=full_name")
            while (next != null) {
                val response = http.get(next, REPO_LIST)
                yieldAll(response.body.orEmpty())
                next = nextLink(response.headers.getFirst(HttpHeaders.LINK))
            }
        }

    /**
     * Every file in the branch, in one request.
     *
     * One recursive listing rather than a request per guessed path: against an estate, guessing costs
     * a request per guess per repository and still misses anything in a directory nobody thought of.
     */
    fun tree(
        org: String,
        repo: String,
        branch: String,
    ): List<GitHubTreeEntry> =
        http
            .getOrNull("/repos/$org/$repo/git/trees/$branch?recursive=1", GitHubTree::class.java)
            ?.tree
            .orEmpty()
            .filter { it.type == BLOB }

    /** One file's contents, or null if the repository does not have it. */
    fun file(
        org: String,
        repo: String,
        path: String,
    ): String? =
        http
            .getOrNull("/repos/$org/$repo/contents/$path", GitHubContent::class.java)
            ?.content
            // GitHub wraps the base64 at 60 characters, which the plain decoder refuses.
            ?.let { String(Base64.getMimeDecoder().decode(it)) }

    /**
     * The repository's CODEOWNERS, from wherever GitHub allows it to live, or null if it has none.
     *
     * Null is an absence of information, not a statement that nobody owns the repository. The caller
     * must not turn it into one.
     */
    fun codeowners(
        org: String,
        repo: String,
    ): Codeowners? {
        CODEOWNERS_PATHS.forEach { path ->
            file(org, repo, path)?.let { return codeownersParser.parse(it) }
        }
        return null
    }

    /** Whether GitHub answers at all, for the connector's health check. */
    fun isReachable(): Boolean = http.isReachable()

    /** `<https://api.github.com/...?page=2>; rel="next", <...>; rel="last"` */
    private fun nextLink(header: String?): URI? =
        header
            ?.split(",")
            ?.firstOrNull { it.contains(NEXT_REL) }
            ?.substringAfter("<")
            ?.substringBefore(">")
            ?.trim()
            ?.let(URI::create)

    companion object {
        /** Everywhere GitHub looks for CODEOWNERS, in the order it looks. */
        val CODEOWNERS_PATHS = listOf("CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS")

        /** GitHub's maximum. Anything smaller multiplies requests against a shared rate limit. */
        private const val PAGE_SIZE = 100
        private const val NEXT_REL = "rel=\"next\""
        private const val BLOB = "blob"

        private val REPO_LIST = object : ParameterizedTypeReference<List<GitHubRepo>>() {}
    }
}
