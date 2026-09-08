package com.repodatagraph.domain.exception

/**
 * One thing wrong with one property, named so a form can put the message next to the input that
 * caused it rather than at the top of the page.
 */
data class PropertyError(
    val field: String,
    val message: String,
)

/**
 * The type in the request path is not one the registry declares.
 *
 * Distinct from [UnknownNodeTypeException], which is the store refusing to build a query from an
 * undeclared label. This one addresses a resource that does not exist, so it is a 404; that one is a
 * malformed write, so it is a 400.
 */
class NodeTypeNotFoundException(
    val type: String,
) : RuntimeException("unknown node type '$type'")

/** The submitted properties do not satisfy the registry. Every problem is reported, not just the first. */
class NodeValidationException(
    val errors: List<PropertyError>,
) : RuntimeException("invalid node: " + errors.joinToString { "${it.field} ${it.message}" })

/**
 * Another node already holds the identity key these properties derive to.
 *
 * The id is returned because the useful next action is almost always to open the node that already
 * exists, not to invent a different name for the same real-world thing.
 */
class NodeExistsException(
    val existingId: String,
) : RuntimeException("a node already exists with this identity: $existingId")

/**
 * An update would move the node to a different identity key.
 *
 * Identity is derived, so changing an identity property does not rename a node: it describes a
 * different thing. Creating that thing is a create, and this refusal is what keeps the two apart.
 */
class ImmutableIdentityException(
    val fields: List<String>,
) : RuntimeException("identity properties are immutable: ${fields.joinToString()}")

/** The node still has relationships, and deleting it would take them with it. */
class NodeHasEdgesException(
    val edgeCount: Int,
) : RuntimeException("node still has $edgeCount edges")
