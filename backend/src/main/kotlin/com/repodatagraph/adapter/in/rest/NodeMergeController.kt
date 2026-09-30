package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.MergeRequest
import com.repodatagraph.adapter.`in`.rest.dto.MergeResponse
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.port.`in`.NodeMergeUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Merging a node into another of its type found to be the same thing (#98, FR-4).
 *
 * `POST /api/v1/nodes/{type}/{key}/merge`, where the key is a trailing capture for the reason
 * [NodeController] gives: a derived key holds slashes. A POST under a node that does not end in
 * `/merge` is no route, and answers 404. A key no path can carry is merged at
 * `/{type}/by-key/merge?key=`. Both need graph:admin as well as graph:write (ScopePolicy).
 */
@RestController
@RequestMapping("/api/v1/nodes")
@Tag(name = "Nodes", description = "Ontology-driven CRUD for every declared node type")
class NodeMergeController(
    private val merges: NodeMergeUseCase,
    private val freshness: FactFreshness,
) {
    @PostMapping("/{type}/$BY_KEY/$MERGE")
    @Operation(summary = "Merge the node at a key no path can carry into another of its type (#98); needs graph:admin")
    fun mergeByKey(
        @PathVariable type: String,
        @RequestParam key: String,
        @RequestBody request: MergeRequest,
    ): ResponseEntity<MergeResponse> = mergeKey(type, key, request)

    @PostMapping("/{type}/{*key}")
    @Operation(
        summary = "Merge a node into another of its type: POST /{type}/{key}/merge (#98); needs graph:admin",
        description =
            "Moves every edge to the node merged into, records the merged key in its previousKeys, and retires the " +
                "merged node as merged, pointing at it. Refused with 409 where the two disagree on their merge scope or " +
                "alias. dryRun previews the merge and changes nothing.",
    )
    fun merge(
        @PathVariable type: String,
        @PathVariable key: String,
        @RequestBody request: MergeRequest,
    ): ResponseEntity<MergeResponse> {
        val path = key.removePrefix("/")
        if (!path.endsWith("/$MERGE")) return ResponseEntity.notFound().build()
        return mergeKey(type, path.removeSuffix("/$MERGE"), request)
    }

    private fun mergeKey(
        type: String,
        key: String,
        request: MergeRequest,
    ): ResponseEntity<MergeResponse> {
        val into =
            request.into?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw NodeValidationException(listOf(PropertyError(INTO, "is required: the node to merge into")))
        return ResponseEntity.ok(MergeResponse.from(merges.merge(type, key, into, request.dryRun), freshness))
    }

    private companion object {
        const val BY_KEY = "by-key"
        const val MERGE = "merge"
        const val INTO = "into"
    }
}
