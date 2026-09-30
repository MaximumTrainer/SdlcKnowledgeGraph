package com.repodatagraph.adapter.`in`.security

import graphql.GraphQLException
import graphql.language.OperationDefinition
import graphql.parser.Parser

/**
 * What a GraphQL document needs (#116, FR-1): `graph:read` for each query or subscription in it,
 * `graph:write` for each mutation, so a document holding both needs both.
 *
 * Every operation in the document counts, not only the one `operationName` selects: the check must
 * not depend on the executor picking the operation this code assumed. And a document that cannot be
 * shown to hold only reads - absent, unparseable, too large to inspect - needs `graph:write` as well,
 * the same deny-by-default the read-only posture applies to GraphQL (#48). A document with no
 * operation at all executes nothing, so it needs only `graph:read`.
 */
object GraphQlScopes {
    private val BOTH = setOf(GraphScope.READ, GraphScope.WRITE)

    fun required(document: String?): Set<GraphScope> {
        val operations = document?.let(::operationsIn) ?: return BOTH
        return if (operations.isEmpty()) {
            setOf(GraphScope.READ)
        } else {
            operations.map { if (it == OperationDefinition.Operation.MUTATION) GraphScope.WRITE else GraphScope.READ }.toSortedSet()
        }
    }

    /** The kind of each operation in [document], or null when it does not parse. */
    private fun operationsIn(document: String): List<OperationDefinition.Operation>? =
        try {
            Parser.parse(document).getDefinitionsOfType(OperationDefinition::class.java).map { it.operation }
        } catch (_: GraphQLException) {
            null
        }
}
