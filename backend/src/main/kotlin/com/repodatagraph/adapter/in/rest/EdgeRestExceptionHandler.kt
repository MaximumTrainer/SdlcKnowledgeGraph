package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.EdgeNotAllowedException
import com.repodatagraph.domain.exception.EdgeValidationException
import com.repodatagraph.domain.exception.SelfEdgeException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns the ways a stated relationship can be refused into responses a caller can act on.
 *
 * Each refusal carries the one thing that makes the next attempt possible: which pairs the ontology
 * allows, or which property was wrong and what it may be. "No" on its own would make a client guess.
 */
@RestControllerAdvice
class EdgeRestExceptionHandler {
    @ExceptionHandler(EdgeNotAllowedException::class)
    fun onEdgeNotAllowed(exception: EdgeNotAllowedException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "error" to "edge not allowed",
                "type" to exception.type,
                "allowed" to exception.allowed.map { (from, to) -> mapOf("from" to from, "to" to to) },
            ),
        )

    @ExceptionHandler(SelfEdgeException::class)
    fun onSelfEdge(exception: SelfEdgeException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "self edge", "nodeId" to exception.nodeId))

    @ExceptionHandler(EdgeValidationException::class)
    fun onEdgeValidation(exception: EdgeValidationException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf("errors" to exception.errors.map { mapOf("field" to it.field, "message" to it.message) }),
        )
}
