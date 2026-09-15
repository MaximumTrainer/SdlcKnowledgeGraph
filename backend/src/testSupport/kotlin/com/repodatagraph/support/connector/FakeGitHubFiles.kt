package com.repodatagraph.support.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import java.util.Base64

/**
 * What is inside a repository: the tree listing, and the contents API that serves one file.
 *
 * Separate from [FakeGitHub] because it is a separate conversation with GitHub. Reading a manifest
 * takes two calls - list the default branch's tree once, then fetch only the paths worth fetching -
 * and a fake that stubbed the second without the first would let a connector pass a test by
 * guessing paths, which against a real estate means a request per guess per repository.
 */
class FakeGitHubFiles(
    private val server: WireMockServer,
) {
    /** Files declared per `org/repo`, so adding one re-publishes a tree containing all of them. */
    private val byRepo = mutableMapOf<String, MutableMap<String, String>>()

    /**
     * A file in the repository's default branch.
     *
     * Registers it in the tree as well as serving its content, because that is the order a connector
     * has to work in: nothing knows the file exists until the tree says so.
     */
    fun has(
        org: String,
        repo: String,
        path: String,
        content: String,
        branch: String = "main",
    ) {
        val files = byRepo.getOrPut("$org/$repo") { linkedMapOf() }
        files[path] = content

        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/git/trees/$branch"))
                .willReturn(jsonResponse(treeJson(files.keys))),
        )
        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/contents/$path"))
                .willReturn(jsonResponse(contentsJson(content))),
        )
    }

    /**
     * CODEOWNERS as the contents API returns it: base64, in a JSON envelope.
     *
     * A null [content] answers 404 from every place GitHub allows the file to live, which is what it
     * says about most repositories - an absence of information rather than an error.
     */
    fun hasCodeowners(
        org: String,
        repo: String,
        content: String?,
        path: String = "CODEOWNERS",
    ) {
        if (content == null) {
            CODEOWNERS_PATHS.forEach { absent ->
                server.stubFor(
                    get(urlPathEqualTo("/repos/$org/$repo/contents/$absent"))
                        .willReturn(aResponse().withStatus(NOT_FOUND).withBody("""{"message":"Not Found"}""")),
                )
            }
            return
        }
        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/contents/$path"))
                .willReturn(jsonResponse(contentsJson(content))),
        )
    }

    internal fun forget() = byRepo.clear()

    private fun contentsJson(content: String) =
        """{"content":"${Base64.getEncoder().encodeToString(content.toByteArray())}","encoding":"base64"}"""

    /** `truncated: false`, because a tree GitHub had to cut short is a case worth its own test. */
    private fun treeJson(paths: Set<String>) =
        paths.joinToString(",", """{"sha":"tree-sha","truncated":false,"tree":[""", "]}") { path ->
            """{"path":"$path","type":"blob","mode":"100644","size":${path.length},"sha":"blob-$path"}"""
        }

    private fun jsonResponse(body: String) =
        aResponse()
            .withStatus(OK)
            .withHeader("Content-Type", "application/json")
            .withHeader(FakeGitHub.REMAINING_HEADER, "5000")
            .withHeader(FakeGitHub.RESET_HEADER, "0")
            .withBody(body)

    private companion object {
        /** Everywhere GitHub looks for CODEOWNERS, in the order it looks. */
        val CODEOWNERS_PATHS = listOf("CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS")
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
