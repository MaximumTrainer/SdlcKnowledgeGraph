package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.NodePageResponse
import com.repodatagraph.adapter.`in`.rest.dto.NodeRequest
import com.repodatagraph.adapter.`in`.rest.dto.NodeResponse
import com.repodatagraph.domain.port.`in`.NodeUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * One CRUD surface for every node type the registry declares.
 *
 * The type is a path variable rather than a set of hand-written endpoints, which is what lets a type
 * added to the ontology be maintained without a backend release. It is never interpolated into a
 * query: the service looks it up in the registry and refuses anything it does not find, so the only
 * labels that reach Cypher are ones the ontology declared.
 *
 * The node segment is a trailing capture because derived keys contain slashes
 * (`github.com/acme/payments`). Encoding them instead would mean allowing encoded slashes in paths,
 * which the servlet container rejects by default and which opens a path-traversal surface.
 */
@RestController
@RequestMapping("/api/v1/nodes")
@Tag(name = "Nodes", description = "Ontology-driven CRUD for every declared node type")
class NodeController(
    private val nodeUseCase: NodeUseCase,
) {
    @PostMapping("/{type}")
    @Operation(summary = "Create a node of a declared type, with a server-derived identity")
    fun create(
        @PathVariable type: String,
        @RequestBody request: NodeRequest,
    ): ResponseEntity<NodeResponse> {
        val created = nodeUseCase.create(type, request.props)
        return ResponseEntity
            .created(URI.create("/api/v1/nodes/$type/${created.key.key}"))
            .body(NodeResponse.from(created))
    }

    @GetMapping("/{type}")
    @Operation(summary = "List nodes of a type, in key order")
    fun list(
        @PathVariable type: String,
        @RequestParam(defaultValue = "$DEFAULT_LIMIT") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<NodePageResponse> =
        ResponseEntity.ok(NodePageResponse.from(nodeUseCase.list(type, limit.coerceIn(1, MAX_LIMIT), cursor)))

    @GetMapping("/{type}/{*key}")
    @Operation(summary = "Get one node by its derived key or its full id")
    fun get(
        @PathVariable type: String,
        @PathVariable key: String,
    ): ResponseEntity<NodeResponse> {
        val node = nodeUseCase.get(type, trimmed(key)) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(NodeResponse.from(node))
    }

    @PutMapping("/{type}/{*key}")
    @Operation(summary = "Replace a node's properties, keeping its identity")
    fun update(
        @PathVariable type: String,
        @PathVariable key: String,
        @RequestBody request: NodeRequest,
    ): ResponseEntity<NodeResponse> = ResponseEntity.ok(NodeResponse.from(nodeUseCase.update(type, trimmed(key), request.props)))

    @DeleteMapping("/{type}/{*key}")
    @Operation(summary = "Delete a node, refusing while it still has relationships")
    fun delete(
        @PathVariable type: String,
        @PathVariable key: String,
        @RequestParam(defaultValue = "false") cascade: Boolean,
    ): ResponseEntity<Void> {
        nodeUseCase.delete(type, trimmed(key), cascade)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    /** A trailing capture keeps the separator that introduced it; the key itself does not have one. */
    private fun trimmed(key: String) = key.removePrefix("/")

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
