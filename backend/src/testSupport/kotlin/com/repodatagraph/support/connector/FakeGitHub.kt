package com.repodatagraph.support.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import java.util.Base64

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

    val baseUrl: String get() = server.baseUrl()

    fun start() {
        server.start()
        stubRateLimit(remaining = RATE_LIMIT_PLENTY)
    }

    fun stop() = server.stop()

    /** Forgets every stub and every recorded request, so scenarios cannot see each other's setup. */
    fun reset() {
        server.resetAll()
        stubRateLimit(remaining = RATE_LIMIT_PLENTY)
    }

    fun requestCount(): Int = server.allServeEvents.size

    /** One page of repositories, with no `Link` header, so the client stops after it. */
    fun hasRepositories(
        org: String,
        repos: List<FakeRepo>,
    ) {
        server.stubFor(
            get(urlPathEqualTo("/orgs/$org/repos"))
                .willReturn(jsonResponse(repos.joinToString(",", "[", "]") { it.json(org) })),
        )
    }

    /**
     * Two pages joined by a `Link` header, exactly as GitHub sends them.
     *
     * A connector that reads only the first page is the classic way to silently ingest a third of an
     * estate, so pagination is set up the way the real API does it rather than asserted on directly.
     */
    fun hasPagedRepositories(
        org: String,
        firstPage: List<FakeRepo>,
        secondPage: List<FakeRepo>,
    ) {
        server.stubFor(
            get(urlPathEqualTo("/orgs/$org/repos"))
                .withQueryParam("page", EqualToPattern("2"))
                .willReturn(jsonResponse(secondPage.joinToString(",", "[", "]") { it.json(org) })),
        )
        server.stubFor(
            get(urlPathEqualTo("/orgs/$org/repos"))
                .willReturn(
                    jsonResponse(firstPage.joinToString(",", "[", "]") { it.json(org) })
                        .withHeader("Link", """<$baseUrl/orgs/$org/repos?page=2>; rel="next""""),
                ),
        )
    }

    /** CODEOWNERS as the contents API returns it: base64, in a JSON envelope. */
    fun hasCodeowners(
        org: String,
        repo: String,
        content: String,
    ) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        server.stubFor(
            get(urlPathMatching("/repos/$org/$repo/contents/.*CODEOWNERS"))
                .willReturn(jsonResponse("""{"content":"$encoded","encoding":"base64"}""")),
        )
    }

    /** Everything not stubbed answers 404, which is what GitHub does for a missing CODEOWNERS. */
    fun hasNoCodeowners(
        org: String,
        repo: String,
    ) {
        server.stubFor(
            get(urlPathMatching("/repos/$org/$repo/contents/.*"))
                .willReturn(aResponse().withStatus(NOT_FOUND).withBody("""{"message":"Not Found"}""")),
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
        private const val RATE_LIMIT_PLENTY = 5000
        private const val OK = 200
        private const val NOT_FOUND = 404
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
