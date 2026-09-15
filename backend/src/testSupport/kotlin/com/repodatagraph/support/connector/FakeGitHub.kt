package com.repodatagraph.support.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import java.time.Instant

/**
 * A GitHub that is not GitHub, over real HTTP.
 *
 * The connector is driven against this rather than against a mocked client, because the things most
 * likely to be wrong are the things a mock cannot get wrong: following `Link` pagination, honouring
 * rate-limit headers, and the exact shape of a payload. A test double for the client would assert
 * that the code calls the methods the test expects, which is a different and much less useful claim.
 */
class FakeGitHub {
    private val server = WireMockServer(options().dynamicPort())

    /** What is inside the repositories: the tree listing, and the contents API. */
    val files = FakeGitHubFiles(server)

    val baseUrl: String get() = server.baseUrl()

    fun start() {
        server.start()
        stubRateLimit(remaining = RATE_LIMIT_PLENTY)
    }

    fun stop() = server.stop()

    /** Forgets every stub and every recorded request, so scenarios cannot see each other's setup. */
    fun reset() {
        server.resetAll()
        files.forget()
        stubRateLimit(remaining = RATE_LIMIT_PLENTY)
    }

    /** The most recent request, for asserting on headers a payload cannot carry. */
    fun lastRequest(): LoggedRequest =
        server.allServeEvents
            .first()
            .request

    /**
     * The repositories the org has, one argument per page of them.
     *
     * More than one page is joined by a `Link` header, exactly as GitHub sends it. A connector that
     * reads only the first page is the classic way to silently ingest a third of an estate, so
     * pagination is set up the way the real API does it rather than asserted on directly.
     */
    fun hasRepositories(
        org: String,
        vararg pages: List<FakeRepo>,
    ) {
        val path = "/orgs/$org/repos"
        // Also on its own, because a webhook reads one repository back rather than listing the org.
        pages.flatMap { it }.forEach { repo ->
            server.stubFor(
                get(urlPathEqualTo("/repos/$org/${repo.name}"))
                    .willReturn(jsonResponse(repo.json(org))),
            )
        }
        pages.forEachIndexed { index, page ->
            val body = page.joinToString(",", "[", "]") { it.json(org) }
            val response =
                if (index == pages.lastIndex) {
                    jsonResponse(body)
                } else {
                    jsonResponse(body).withHeader("Link", """<$baseUrl$path?page=${index + 2}>; rel="next"""")
                }
            val stub = get(urlPathEqualTo(path)).willReturn(response)
            // The first page answers a request that names no page at all; the rest are addressed by
            // the number the `Link` header sent the client to.
            server.stubFor(if (index == 0) stub else stub.withQueryParam("page", EqualToPattern("${index + 1}")))
        }
    }

    /**
     * The rate limit spent, as GitHub reports it: a 403 with `X-RateLimit-Remaining: 0`.
     *
     * The status alone does not distinguish this from "your token may not do that", which is why the
     * header is what the client has to read. Given [thenReturning], the limit lifts after one
     * refusal, so waiting for a reset can be told apart from giving up on one.
     */
    fun hasExhaustedRateLimit(
        org: String,
        resetsAt: Instant,
        thenReturning: List<FakeRepo>? = null,
    ) {
        stubRateLimit(remaining = 0, resetEpochSeconds = resetsAt.epochSecond)
        val path = "/orgs/$org/repos"
        val refusal =
            aResponse()
                .withStatus(FORBIDDEN)
                .withHeader("Content-Type", "application/json")
                .withHeader(REMAINING_HEADER, "0")
                .withHeader(RESET_HEADER, resetsAt.epochSecond.toString())
                .withBody("""{"message":"API rate limit exceeded"}""")

        if (thenReturning == null) {
            server.stubFor(get(urlPathEqualTo(path)).willReturn(refusal))
            return
        }
        val scenario = "rate-limit-$org"
        server.stubFor(
            get(urlPathEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("reset")
                .willReturn(refusal),
        )
        server.stubFor(
            get(urlPathEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs("reset")
                .willReturn(jsonResponse(thenReturning.joinToString(",", "[", "]") { it.json(org) })),
        )
    }

    /**
     * Fails before answering, so a retry can be told from a refusal.
     *
     * [times] of [ALWAYS] never answers, which is how giving up is told from retrying for ever.
     */
    fun failsThenReturns(
        org: String,
        times: Int,
        repos: List<FakeRepo> = emptyList(),
    ) {
        val path = "/orgs/$org/repos"
        val failure = aResponse().withStatus(SERVER_ERROR).withBody("""{"message":"try again"}""")
        if (times == ALWAYS) {
            server.stubFor(get(urlPathEqualTo(path)).willReturn(failure))
            return
        }
        val scenario = "repos-$org"
        repeat(times) { attempt ->
            server.stubFor(
                get(urlPathEqualTo(path))
                    .inScenario(scenario)
                    .whenScenarioStateIs(if (attempt == 0) Scenario.STARTED else "failed-$attempt")
                    .willSetStateTo("failed-${attempt + 1}")
                    .willReturn(failure),
            )
        }
        server.stubFor(
            get(urlPathEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs(if (times == 0) Scenario.STARTED else "failed-$times")
                .willReturn(jsonResponse(repos.joinToString(",", "[", "]") { it.json(org) })),
        )
    }

    fun stubRateLimit(
        remaining: Int,
        resetEpochSeconds: Long = 0,
    ) {
        server.stubFor(
            get(urlPathEqualTo("/rate_limit"))
                .willReturn(
                    jsonResponse("""{"resources":{"core":{"remaining":$remaining}}}""")
                        .withHeader(REMAINING_HEADER, remaining.toString())
                        .withHeader(RESET_HEADER, resetEpochSeconds.toString()),
                ),
        )
    }

    private fun jsonResponse(body: String) =
        aResponse()
            .withStatus(OK)
            .withHeader("Content-Type", "application/json")
            .withHeader(REMAINING_HEADER, RATE_LIMIT_PLENTY.toString())
            .withHeader(RESET_HEADER, "0")
            .withBody(body)

    companion object {
        const val REMAINING_HEADER = "X-RateLimit-Remaining"
        const val RESET_HEADER = "X-RateLimit-Reset"

        /** A failure count that never recovers. */
        const val ALWAYS = -1

        private const val RATE_LIMIT_PLENTY = 5000
        private const val OK = 200
        private const val FORBIDDEN = 403
        private const val SERVER_ERROR = 500
    }
}

/** The handful of repository fields this issue reads, as GitHub spells them. */
data class FakeRepo(
    val name: String,
    val topics: List<String> = emptyList(),
    val archived: Boolean = false,
    val defaultBranch: String = "main",
    val nodeId: String = "R_${name.lowercase()}",
    val language: String? = null,
    val description: String? = null,
    val pushedAt: String = "2026-09-01T10:00:00Z",
) {
    fun json(org: String): String =
        """
        {
          "node_id": "$nodeId",
          "name": "$name",
          "full_name": "$org/$name",
          "html_url": "https://github.com/$org/$name",
          "default_branch": "$defaultBranch",
          "description": ${description?.let { "\"$it\"" } ?: "null"},
          "language": ${language?.let { "\"$it\"" } ?: "null"},
          "archived": $archived,
          "visibility": "private",
          "pushed_at": "$pushedAt",
          "topics": ${topics.joinToString(",", "[", "]") { "\"$it\"" }}
        }
        """.trimIndent()
}
