package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Creating and removing relationships.
 *
 * Node ids contain slashes, so they travel in the body or as query parameters rather than as path
 * segments: an encoded slash in a path is rejected by the servlet container, and enabling it would
 * open a path-traversal surface for the sake of prettier URLs.
 */
@RestController
@RequestMapping("/api/v1/edges")
@Tag(name = "Edges", description = "Relationships between nodes")
class EdgeController(
    private val graphStore: GraphStore,
) {
    data class EdgeRequest(
        val type: String,
        val fromId: String,
        val toId: String,
        val props: Map<String, Any?> = emptyMap(),
    )

    @PostMapping
    @Operation(summary = "Create or update a relationship between two existing nodes")
    fun createEdge(
        @RequestBody request: EdgeRequest,
    ): ResponseEntity<Map<String, Any>> {
        val edge =
            graphStore.upsertEdge(
                GraphEdge(
                    type = request.type,
                    from = NodeKey.parse(request.fromId),
                    to = NodeKey.parse(request.toId),
                    props = request.props,
                    provenance = Provenance.manual(),
                ),
            )
        return ResponseEntity.ok(mapOf("type" to edge.type, "fromId" to edge.from.id, "toId" to edge.to.id))
    }

    @DeleteMapping
    @Operation(summary = "Remove a relationship")
    fun deleteEdge(
        @RequestParam type: String,
        @RequestParam fromId: String,
        @RequestParam toId: String,
    ): ResponseEntity<Void> {
        val removed = graphStore.deleteEdge(type, NodeKey.parse(fromId), NodeKey.parse(toId))
        return if (removed) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }
}
