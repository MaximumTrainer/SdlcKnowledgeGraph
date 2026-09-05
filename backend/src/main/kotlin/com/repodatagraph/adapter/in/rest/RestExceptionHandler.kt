package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidEdgeException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.UnknownEdgeTypeException
import com.repodatagraph.domain.exception.UnknownNodeTypeException
import com.repodatagraph.domain.ontology.IdentityResolutionException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates domain failures into responses.
 *
 * Messages name the thing that was wrong, never the internals: no stack traces, no Cypher and no
 * configuration reach the client, because an error response is an information disclosure surface.
 */
@RestControllerAdvice
class RestExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NodeNotFoundException::class)
    fun onNodeNotFound(exception: NodeNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("error" to "node not found", "missing" to exception.missing.map { it.id }),
        )

    @ExceptionHandler(UnknownNodeTypeException::class)
    fun onUnknownNodeType(exception: UnknownNodeTypeException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "unknown node type", "type" to exception.type))

    @ExceptionHandler(UnknownEdgeTypeException::class)
    fun onUnknownEdgeType(exception: UnknownEdgeTypeException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "unknown edge type", "type" to exception.type))

    @ExceptionHandler(InvalidEdgeException::class)
    fun onInvalidEdge(exception: InvalidEdgeException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "invalid edge", "detail" to exception.message.orEmpty()))

    @ExceptionHandler(IdentityResolutionException::class)
    fun onIdentityResolution(exception: IdentityResolutionException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "cannot derive identity", "detail" to exception.message.orEmpty()))

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(exception: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        log.debug("rejected a malformed request", exception)
        return ResponseEntity.badRequest().body(mapOf("error" to "invalid request", "detail" to exception.message.orEmpty()))
    }
}
