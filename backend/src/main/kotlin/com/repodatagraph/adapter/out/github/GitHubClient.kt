package com.repodatagraph.adapter.out.github

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Instant
import java.util.Base64

/** GitHub said something this connector cannot act on. */
sealed class GitHubException(
    message: String,
) : RuntimeException(message)

/**
 * The rate limit is spent.
 *
 * Carries the reset, because "why did the sync stop" should be answerable from the run record rather
 * than by going and asking GitHub. Not retried: the wait is up to an hour, and a run that blocks a
 * worker thread for an hour is worse than a run that fails and says when to come back.
 */
class GitHubRateLimitException(
    val resetsAt: Instant,
) : GitHubException("GitHub's rate limit is spent; it resets at $resetsAt")

/** GitHub failed, repeatedly. Retried before this is raised. */
class GitHubUnavailableException(
    message: String,
) : GitHubException(message)

/** GitHub refused: the token cannot see this, or it does not exist. Retrying would not help. */
class GitHubRefusedException(
    message: String,
) : GitHubException(message)

/**
 * The GitHub REST API, as much of it as this connector needs.
 *
 * Three things here are not incidental. Pagination is followed to the end, because a connector that
 * reads one page ingests part of an estate and reports success. A spent rate limit is recognised from
 * the header rather than inferred from a 403, which also means "your token may not do that". And a
 * 5xx is retried while a 404 is an answer - most repositories have no CODEOWNERS, so treating its
 * absence as an error would fail nearly every run.
 */
@Component
class GitHubClient(
    private val properties: GitHubProperties,
    builder: RestClient.Builder,
    private val codeownersParser: CodeownersParser = CodeownersParser(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client =
        builder
            .baseUrl(properties.baseUrl)
            .defaultHeader(HttpHeaders.ACCEPT, GITHUB_JSON)
            // Pinned, because GitHub changes response shapes behind an unpinned version and the first
            // sign of it would be a mapper failing on a field that used to be there.
            .defaultHeader(API_VERSION_HEADER, API_VERSION)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.token}")
            .messageConverters { converters ->
                converters.add(0, MappingJackson2HttpMessageConverter(GITHUB_MAPPER))
            }.build()

    /**
     * Every repository in the org, page by page.
     *
     * A sequence rather than a list: an org with ten thousand repositories is ten thousand objects
     * held at once otherwise, and the caller writes each page as it arrives anyway.
     */
    fun repositories(): Sequence<GitHubRepo> =
        sequence {
            var next: URI? = URI.create("${properties.baseUrl}/orgs/${properties.org}/repos?per_page=$PAGE_SIZE&sort=full_name")
            while (next != null) {
                val page = repositoryPage(next)
                yieldAll(page.repos)
                next = page.next
            }
        }

    /**
     * The repository's CODEOWNERS, from wherever GitHub allows it to live, or null if it has none.
     *
     * Null is an absence of information, not a statement that nobody owns the repository. The caller
     * must not turn it into one.
     */
    fun codeowners(repo: String): Codeowners? {
        CODEOWNERS_PATHS.forEach { path ->
            val content = contentAt("/repos/${properties.org}/$repo/contents/$path")
            if (content != null) return codeownersParser.parse(content)
        }
        return null
    }

    /** Whether GitHub answers at all, for the connector's health check. */
    fun isReachable(): Boolean =
        try {
            withRetries {
                client.get().uri("/rate_limit").exchange { _, response ->
                    refuseIfRateLimited(response.headers, response.statusCode.value())
                    response.statusCode.is2xxSuccessful
                } ?: false
            }
        } catch (unreachable: GitHubException) {
            log.warn("GitHub is not reachable: {}", unreachable.message)
            false
        }

    private fun repositoryPage(uri: URI): RepoPage =
        withRetries {
            val page =
                client.get().uri(uri).exchange { _, response ->
                    refuseIfRateLimited(response.headers, response.statusCode.value())
                    failUnlessOk(response.statusCode.value(), uri.path)
                    RepoPage(
                        repos = response.bodyTo(REPO_LIST) ?: emptyList(),
                        next = nextLink(response.headers.getFirst(HttpHeaders.LINK)),
                    )
                }
            // `exchange` is declared nullable because a handler may return null; this one cannot.
            checkNotNull(page)
        }

    /** Null for a 404, which is what GitHub says about a file a repository does not have. */
    private fun contentAt(path: String): String? =
        withRetries {
            client.get().uri(path).exchange { _, response ->
                refuseIfRateLimited(response.headers, response.statusCode.value())
                when {
                    response.statusCode.value() == NOT_FOUND -> null
                    else -> {
                        failUnlessOk(response.statusCode.value(), path)
                        decode(response.bodyTo(GitHubContent::class.java))
                    }
                }
            }
        }

    private fun decode(envelope: GitHubContent?): String? {
        val encoded = envelope?.content ?: return null
        // GitHub wraps the base64 at 60 characters, which the plain decoder refuses.
        return String(Base64.getMimeDecoder().decode(encoded))
    }

    /**
     * Retries what is worth retrying.
     *
     * A 5xx from GitHub is usually a moment rather than a condition, and failing a whole run on one
     * would make syncing a large org close to impossible. A refusal and a spent rate limit are not
     * retried: neither would answer differently for being asked again.
     */
    private fun <T> withRetries(call: () -> T): T {
        var lastFailure: GitHubUnavailableException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return call()
            } catch (unavailable: GitHubUnavailableException) {
                lastFailure = unavailable
                log.info("GitHub failed ({}), attempt {} of {}", unavailable.message, attempt + 1, MAX_ATTEMPTS)
                Thread.sleep(BACKOFF_MILLIS * (attempt + 1))
            }
        }
        throw checkNotNull(lastFailure)
    }

    /**
     * A 403 alone does not mean the rate limit is spent - it also means the token may not do that -
     * so the header decides, and the reset comes back with the refusal.
     */
    private fun refuseIfRateLimited(
        headers: HttpHeaders,
        status: Int,
    ) {
        if (status != FORBIDDEN && status != TOO_MANY_REQUESTS) return
        val remaining = headers.getFirst(REMAINING_HEADER)?.toIntOrNull()
        if (remaining != null && remaining > 0) return
        val reset = headers.getFirst(RESET_HEADER)?.toLongOrNull() ?: 0L
        throw GitHubRateLimitException(Instant.ofEpochSecond(reset))
    }

    private fun failUnlessOk(
        status: Int,
        what: String,
    ) {
        when {
            status < BAD_REQUEST -> return
            status >= SERVER_ERROR -> throw GitHubUnavailableException("GitHub answered $status for $what")
            else -> throw GitHubRefusedException("GitHub answered $status for $what")
        }
    }

    /** `<https://api.github.com/...?page=2>; rel="next", <...>; rel="last"` */
    private fun nextLink(header: String?): URI? =
        header
            ?.split(",")
            ?.firstOrNull { it.contains(NEXT_REL) }
            ?.substringAfter("<")
            ?.substringBefore(">")
            ?.trim()
            ?.let(URI::create)

    private data class RepoPage(
        val repos: List<GitHubRepo>,
        val next: URI?,
    )

    companion object {
        const val API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val API_VERSION = "2022-11-28"
        const val GITHUB_JSON = "application/vnd.github+json"

        /** Everywhere GitHub looks for CODEOWNERS, in the order it looks. */
        val CODEOWNERS_PATHS = listOf("CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS")

        private const val REMAINING_HEADER = "X-RateLimit-Remaining"
        private const val RESET_HEADER = "X-RateLimit-Reset"

        /** GitHub's maximum. Anything smaller multiplies requests against a shared rate limit. */
        private const val PAGE_SIZE = 100
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MILLIS = 200L
        private const val NEXT_REL = "rel=\"next\""
        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500

        /**
         * GitHub's own mapper, not the application's.
         *
         * The payloads are snake_case with ISO instants, which is nothing to do with how this
         * application renders its own API - and a connector that broke when the API's serialisation
         * was changed would be coupled to something it has no relationship with.
         */
        private val GITHUB_MAPPER =
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .addModule(JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()

        private val REPO_LIST = object : ParameterizedTypeReference<List<GitHubRepo>>() {}
    }
}
