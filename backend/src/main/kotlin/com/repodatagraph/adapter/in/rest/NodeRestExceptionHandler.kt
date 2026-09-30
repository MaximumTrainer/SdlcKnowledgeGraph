package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.InvalidGitRemoteException
import com.repodatagraph.domain.exception.InvalidMergeException
import com.repodatagraph.domain.exception.ManagedNodeTypeException
import com.repodatagraph.domain.exception.MergeConflictException
import com.repodatagraph.domain.exception.MergeIntoRetiredException
import com.repodatagraph.domain.exception.NodeAlreadyMergedException
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
@Suppress("TooManyFunctions") // One handler per refusal, each with the body its caller acts on.
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

    /**
     * A url that is not a git remote is the caller's to correct, so it is a 400 rather than a 500.
     *
     * Reported as its own error rather than as a validation failure on three properties the caller
     * never sent: `host`, `org` and `name` are derived from the url, so telling someone they are
     * missing would be telling them to fix the wrong thing. The reason says what is actually wrong
     * with the url (#8).
     */
    @ExceptionHandler(InvalidGitRemoteException::class)
    fun onInvalidGitRemote(exception: InvalidGitRemoteException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf("error" to "invalid git remote", "reason" to exception.reason, "input" to exception.input),
        )

    @ExceptionHandler(NodeValidationException::class)
    fun onNodeValidation(exception: NodeValidationException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf("errors" to exception.errors.map { mapOf("field" to it.field, "message" to it.message) }),
        )

    /**
     * The same refusal whether the key or the alias collided, since either way the thing already
     * exists (#88); `alias` says which it was, and is absent for a key.
     */
    @ExceptionHandler(NodeExistsException::class)
    fun onNodeExists(exception: NodeExistsException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "node exists", "existingId" to exception.existingId) +
                listOfNotNull(exception.alias?.let { "alias" to it }),
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

    /** Forbidden to everyone here, whoever they are: the type is written through its own API (#115). */
    @ExceptionHandler(ManagedNodeTypeException::class)
    fun onManagedNodeType(exception: ManagedNodeTypeException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            mapOf("error" to "managed node type", "type" to exception.type, "managedAt" to exception.managedAt),
        )

    /**
     * Two nodes the registry says differ (#98): every conflicting field with both values, since which
     * is wrong is the caller's to find out. `fields` names them alone, for a form.
     */
    @ExceptionHandler(MergeConflictException::class)
    fun onMergeConflict(exception: MergeConflictException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "identity conflict",
                "fields" to exception.conflicts.map { it.field },
                "conflicts" to exception.conflicts.map { mapOf("field" to it.field, "from" to it.from, "into" to it.into) },
            ),
        )

    /** A merge that could never make sense, into itself or across types (#98). */
    @ExceptionHandler(InvalidMergeException::class)
    fun onInvalidMerge(exception: InvalidMergeException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("error" to "invalid merge", "reason" to exception.reason))

    @ExceptionHandler(MergeIntoRetiredException::class)
    fun onMergeIntoRetired(exception: MergeIntoRetiredException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "merge into a retired node", "into" to exception.into.id))

    /** A node merged already, merged again or written again: named with where it went (#98). */
    @ExceptionHandler(NodeAlreadyMergedException::class)
    fun onNodeAlreadyMerged(exception: NodeAlreadyMergedException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "node already merged", "nodeId" to exception.node.id, "mergedInto" to exception.mergedInto.id),
        )
}
