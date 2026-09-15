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
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** GitHub said something this connector cannot act on. */
sealed class GitHubException(
    message: String,
) : RuntimeException(message)

/**
 * The rate limit is spent.
 *
 * Carries the reset, because "why did the sync stop" should be answerable from the run record rather
 * than by going and asking GitHub.
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

/** A response, kept with its headers because pagination lives in one. */
data class GitHubResponse<T>(
    val body: T?,
    val headers: HttpHeaders,
)

/**
 * Talking to GitHub: authentication, retries, rate limits, and nothing about repositories.
 *
 * Separate from [GitHubClient] because these are the decisions that have nothing to do with what is
 * being asked for. A spent rate limit is handled the same way whether the request was for an org's
 * repositories or for one file inside one of them, and the alternative is that policy appearing once
 * per endpoint with three subtly different versions of "should this be retried".
 */
@Component
class GitHubHttp(
    private val properties: GitHubProperties,
    builder: RestClient.Builder,
    private val clock: Clock = Clock.systemUTC(),
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

    /** A GET that must succeed, with its headers. */
    fun <T> get(
        uri: URI,
        type: ParameterizedTypeReference<T>,
    ): GitHubResponse<T> =
        withRetries {
            val response =
                client.get().uri(uri).exchange { _, response ->
                    refuseIfRateLimited(response.headers, response.statusCode.value())
                    failUnlessOk(response.statusCode.value(), uri.path)
                    GitHubResponse(response.bodyTo(type), response.headers)
                }
            // `exchange` is declared nullable because a handler may return null; this one cannot.
            checkNotNull(response)
        }

    /**
     * A GET where absence is an answer.
     *
     * 404 is what GitHub says about a file a repository does not have, which is most files in most
     * repositories. Treating it as an error would fail nearly every run.
     */
    fun <T> getOrNull(
        path: String,
        type: Class<T>,
    ): T? =
        withRetries {
            client.get().uri(path).exchange { _, response ->
                refuseIfRateLimited(response.headers, response.statusCode.value())
                when (response.statusCode.value()) {
                    NOT_FOUND, CONFLICT -> null
                    else -> {
                        failUnlessOk(response.statusCode.value(), path)
                        response.bodyTo(type)
                    }
                }
            }
        }

    /** Whether GitHub answers at all, for the connector's health check. */
    fun isReachable(): Boolean =
        try {
            withRetries {
                client.get().uri("/rate_limit").exchange { _, response ->
                    refuseIfRateLimited(response.headers, response.statusCode.value())
                    response.statusCode.is2xxSuccessful
                } == true
            }
        } catch (unreachable: GitHubException) {
            log.warn("GitHub is not reachable: {}", unreachable.message)
            false
        }

    /**
     * Retries what is worth retrying, and waits out a rate limit only if the wait is short.
     *
     * A 5xx from GitHub is usually a moment rather than a condition, and failing a whole run on one
     * would make syncing a large org close to impossible. A rate limit is different: it is not an
     * error but an instruction, so the client waits when the reset is seconds away and gives up when
     * it is an hour away - because syncs run on a pool of four threads, and a run that sleeps for an
     * hour stops every other connector as surely as a deadlock would. A refusal is never retried.
     */
    private fun <T> withRetries(call: () -> T): T {
        var lastFailure: GitHubException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return call()
            } catch (unavailable: GitHubUnavailableException) {
                lastFailure = unavailable
                log.info("GitHub failed ({}), attempt {} of {}", unavailable.message, attempt + 1, MAX_ATTEMPTS)
                Thread.sleep(BACKOFF_MILLIS * (attempt + 1))
            } catch (limited: GitHubRateLimitException) {
                lastFailure = limited
                waitOutOrRethrow(limited)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun waitOutOrRethrow(limited: GitHubRateLimitException) {
        val wait = Duration.between(Instant.now(clock), limited.resetsAt)
        if (wait > Duration.ofSeconds(properties.waitForResetSeconds)) throw limited
        log.info("GitHub's rate limit resets in {}s; waiting for it", wait.seconds.coerceAtLeast(0))
        Thread.sleep(wait.toMillis().coerceAtLeast(0))
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

    companion object {
        const val API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val API_VERSION = "2022-11-28"
        const val GITHUB_JSON = "application/vnd.github+json"

        private const val REMAINING_HEADER = "X-RateLimit-Remaining"
        private const val RESET_HEADER = "X-RateLimit-Reset"
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MILLIS = 200L
        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404

        /** What GitHub answers for a repository with no commits: nothing to read, not a failure. */
        private const val CONFLICT = 409
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
    }
}
