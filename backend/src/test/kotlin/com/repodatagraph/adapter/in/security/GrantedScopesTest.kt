package com.repodatagraph.adapter.`in`.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which graph scopes a token holds (#116). OAuth 2 puts them in `scope`, a space-separated string
 * (RFC 6749, RFC 9068); some issuers use `scp`, a JSON array. Either is read, and both when a token
 * has both. Only `graph:` scopes are kept: `openid`, `profile` and the rest are not the API's business.
 */
class GrantedScopesTest {
    @Test
    fun `reads the space-separated scope claim`() {
        assertEquals(
            listOf("graph:read", "graph:write"),
            GrantedScopes.graphScopesOf(
                mapOf(
                    "scope" to "openid graph:write profile graph:read",
                ),
            ),
        )
    }

    @Test
    fun `reads an scp array`() {
        assertEquals(listOf("graph:read"), GrantedScopes.graphScopesOf(mapOf("scp" to listOf("graph:read", "email"))))
    }

    @Test
    fun `reads an scp string, and a scope array, as some issuers write them`() {
        assertEquals(listOf("graph:write"), GrantedScopes.graphScopesOf(mapOf("scp" to "graph:write")))
        assertEquals(listOf("graph:read"), GrantedScopes.graphScopesOf(mapOf("scope" to listOf("graph:read"))))
    }

    @Test
    fun `joins scope and scp when a token has both, without repeating one`() {
        assertEquals(
            listOf("graph:read", "graph:write"),
            GrantedScopes.graphScopesOf(mapOf("scope" to "graph:read", "scp" to listOf("graph:write", "graph:read"))),
        )
    }

    @Test
    fun `tolerates extra whitespace`() {
        assertEquals(listOf("graph:read", "graph:write"), GrantedScopes.graphScopesOf(mapOf("scope" to "  graph:read\tgraph:write  ")))
    }

    @Test
    fun `a token without scopes holds none`() {
        assertEquals(emptyList<String>(), GrantedScopes.graphScopesOf(emptyMap()))
        assertEquals(emptyList<String>(), GrantedScopes.graphScopesOf(mapOf("scope" to "")))
        assertEquals(emptyList<String>(), GrantedScopes.graphScopesOf(mapOf("scope" to "openid profile email")))
    }

    @Test
    fun `a scope that only starts like a graph scope is not one`() {
        assertEquals(emptyList<String>(), GrantedScopes.graphScopesOf(mapOf("scope" to "graph graphs:read GRAPH:READ")))
    }

    @Test
    fun `holds a requirement only when every scope in it is held`() {
        val held = GrantedScopes.graphScopesOf(mapOf("scope" to "graph:read"))

        assertEquals(emptySet<GraphScope>(), GrantedScopes.missing(setOf(GraphScope.READ), held))
        assertEquals(setOf(GraphScope.WRITE), GrantedScopes.missing(setOf(GraphScope.READ, GraphScope.WRITE), held))
    }
}
