package com.repodatagraph.adapter.`in`.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a GraphQL document needs (#116, FR-1): graph:read for a query, graph:write for a mutation, and
 * both for a document holding both. Every operation in the document counts, not only the one
 * `operationName` picks, and a document that cannot be shown to hold only reads needs graph:write
 * as well - the same deny-by-default the read-only posture applies (#48).
 */
class GraphQlScopesTest {
    @Test
    fun `a query needs graph read`() {
        assertEquals(setOf(GraphScope.READ), GraphQlScopes.required("{ repositories { id } }"))
        assertEquals(setOf(GraphScope.READ), GraphQlScopes.required("query Repos { repositories { id } }"))
    }

    @Test
    fun `a subscription is a read`() {
        assertEquals(setOf(GraphScope.READ), GraphQlScopes.required("subscription { repositories { id } }"))
    }

    @Test
    fun `a mutation needs graph write`() {
        assertEquals(setOf(GraphScope.WRITE), GraphQlScopes.required("""mutation { deleteRepository(id: "r1") }"""))
    }

    @Test
    fun `a document holding a query and a mutation needs both`() {
        assertEquals(
            setOf(GraphScope.READ, GraphScope.WRITE),
            GraphQlScopes.required("""query A { repositories { id } } mutation B { deleteRepository(id: "r1") }"""),
        )
    }

    @Test
    fun `fragments alone are no operation, and still a read`() {
        assertEquals(setOf(GraphScope.READ), GraphQlScopes.required("fragment F on Repository { id }"))
    }

    @Test
    fun `a document that does not parse needs both`() {
        assertEquals(setOf(GraphScope.READ, GraphScope.WRITE), GraphQlScopes.required("mutation { this is not graphql"))
    }

    @Test
    fun `no document at all needs both`() {
        assertEquals(setOf(GraphScope.READ, GraphScope.WRITE), GraphQlScopes.required(null))
    }
}
