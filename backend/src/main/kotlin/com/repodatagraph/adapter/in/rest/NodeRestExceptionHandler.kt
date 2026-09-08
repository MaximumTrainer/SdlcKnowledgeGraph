package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeHasEdgesException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.exception.NodeValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns the ways a hand-edited node can be refused into responses a form can act on.
 *
 * Separate from [RestExceptionHandler], which maps failures of the graph store itself. These are
 * refusals of a user's edit, and each carries the one thing the editing screen needs to say what to
 * do next: which field, or which node already holds the identity, or how much a delete would take
 * with it. Messages name the input that was wrong and nothing about the internals.
 */
@RestControllerAdvice
class NodeRestExceptionHandler {
    /**
     * A type in the path that the registry does not declare addresses a resource that does not
     * exist, so it is a 404. [UnknownNodeTypeException] below is a malformed write, so it is a 400.
     */
    @ExceptionHandler(NodeTypeNotFoundException::class)
    fun onNodeTypeNotFound(exception: NodeTypeNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("error" to "unknown node type", "type" to exception.type),
        )

    @ExceptionHandler(NodeValidationException::class)
    fun onNodeValidation(exception: NodeValidationException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf("errors" to exception.errors.map { mapOf("field" to it.field, "message" to it.message) }),
        )

    @ExceptionHandler(NodeExistsException::class)
    fun onNodeExists(exception: NodeExistsException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "node exists", "existingId" to exception.existingId),
        )

    @ExceptionHandler(ImmutableIdentityException::class)
    fun onImmutableIdentity(exception: ImmutableIdentityException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "identity properties are immutable", "fields" to exception.fields),
        )

    @ExceptionHandler(NodeHasEdgesException::class)
    fun onNodeHasEdges(exception: NodeHasEdgesException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "node has edges", "edgeCount" to exception.edgeCount),
        )
}
