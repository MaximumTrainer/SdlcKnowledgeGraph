package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.NodeTypeResponse
import com.repodatagraph.adapter.`in`.rest.dto.OntologyResponse
import com.repodatagraph.adapter.`in`.rest.dto.UnknownTypeResponse
import com.repodatagraph.domain.ontology.OntologyRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

/**
 * Publishes the ontology so consumers can build against the model rather than restating it.
 *
 * The generic editing screen renders its form fields from this response, which is what lets a new
 * node type appear in the user interface without a frontend release.
 */
@RestController
@RequestMapping("/api/v1/ontology")
@Tag(name = "Ontology", description = "The declared contract for what may exist in the graph")
class OntologyController(
    registry: OntologyRegistry,
) {
    // The ontology is immutable for the lifetime of the process, so the response and its ETag are
    // computed once rather than on every request.
    private val response = OntologyResponse.from(registry)
    private val nodeTypesByName = response.nodeTypes.associateBy { it.name }
    private val etag = "\"${response.hashCode().toUInt().toString(radix = 16)}\""

    @GetMapping
    @Operation(summary = "The whole ontology: node types, edge types and their inverses")
    fun ontology(): ResponseEntity<OntologyResponse> = cacheable().eTag(etag).body(response)

    @GetMapping("/nodes/{type}")
    @Operation(summary = "A single node type")
    fun nodeType(
        @PathVariable type: String,
    ): ResponseEntity<Any> {
        // The path variable is untrusted input. It is only ever used as a lookup key against types
        // the registry already declares, never interpolated into a query or reflected as markup, so
        // an unknown or hostile value can do nothing but produce this 404.
        val nodeType: NodeTypeResponse =
            nodeTypesByName[type]
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(UnknownTypeResponse("unknown node type", type))
        return cacheable().eTag(etag).body(nodeType)
    }

    private fun cacheable(): ResponseEntity.BodyBuilder =
        ResponseEntity.ok().cacheControl(CacheControl.maxAge(CACHE_SECONDS, TimeUnit.SECONDS).cachePublic())

    private companion object {
        const val CACHE_SECONDS = 300L
    }
}
