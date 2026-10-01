package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzResource
import com.repodatagraph.domain.policy.ResourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a request does and to what, as the policy is asked (#30 FR5): the action follows the route's
 * scopes, and the resource is a node type wherever the path names one.
 */
class RouteAuthzTest {
    private fun actions(
        method: String,
        path: String,
        document: String? = null,
    ): Set<AuthzAction> = RouteAuthz.actions(method, path, ScopePolicy.requirementFor(method, path)!!, document)

    private fun resource(
        method: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        document: String? = null,
    ): AuthzResource = RouteAuthz.resource(method, path, { parameters[it] }, document)

    @Test
    fun `reads are read, and reads sent as a POST are query`() {
        assertEquals(setOf(AuthzAction.READ), actions("GET", "/api/v1/nodes/Repository"))
        assertEquals(setOf(AuthzAction.QUERY), actions("POST", "/api/v1/impact"))
        assertEquals(setOf(AuthzAction.QUERY), actions("POST", "/api/v1/policy/explain"))
    }

    @Test
    fun `writes are named by what they do`() {
        assertEquals(setOf(AuthzAction.CREATE), actions("POST", "/api/v1/nodes/Repository"))
        assertEquals(setOf(AuthzAction.UPDATE), actions("PUT", "/api/v1/nodes/Repository/github.com/acme/payments"))
        assertEquals(setOf(AuthzAction.DELETE), actions("DELETE", "/api/v1/nodes/Repository/github.com/acme/payments"))
        assertEquals(setOf(AuthzAction.LINK), actions("POST", "/api/v1/edges"))
        assertEquals(setOf(AuthzAction.SYNC), actions("POST", "/api/v1/connectors/github/sync"))
        assertEquals(setOf(AuthzAction.ADMIN), actions("POST", "/api/v1/lifecycle/migrations/apply"))
    }

    @Test
    fun `a GraphQL document is asked about each thing it does`() {
        assertEquals(setOf(AuthzAction.QUERY), actions("POST", "/graphql", "{ repositories { id } }"))
        assertEquals(
            setOf(AuthzAction.QUERY, AuthzAction.DELETE),
            actions("POST", "/graphql", "query Q { repositories { id } } mutation M { deleteRepository(id: \"x\") }"),
        )
        assertEquals(setOf(AuthzAction.QUERY, AuthzAction.UPDATE), actions("POST", "/graphql", "not graphql"))
    }

    @Test
    fun `a node route names its type, and its key wherever the path or query carries one`() {
        assertEquals(
            AuthzResource(ResourceKind.NODE, type = "Repository", key = "github.com/acme/payments", filtered = true),
            resource("GET", "/api/v1/nodes/Repository/github.com/acme/payments"),
        )
        assertEquals(
            AuthzResource(ResourceKind.NODE, type = "Repository", key = "github.com/acme/payments"),
            resource("POST", "/api/v1/nodes/Repository/github.com/acme/payments/merge"),
        )
        assertEquals(
            AuthzResource(ResourceKind.NODE, type = "Repository", key = "a/b", filtered = true),
            resource("GET", "/api/v1/nodes/Repository/by-key", mapOf("key" to "a/b")),
        )
        assertEquals(
            AuthzResource(ResourceKind.NODE, type = "ServicePrincipal", filtered = true),
            resource("GET", "/api/v1/service-principals"),
        )
    }

    @Test
    fun `only the reads the policy filters are marked filtered`() {
        assertEquals(true, resource("GET", "/api/v1/edges").filtered)
        assertEquals(true, resource("GET", "/api/v1/graph/neighbourhood").filtered)
        assertEquals(false, resource("GET", "/api/v1/repositories").filtered)
        assertEquals(false, resource("GET", "/api/v1/lifecycle/history").filtered)
        assertEquals(true, resource("GET", "/api/v1/sync-runs").filtered, "holds nothing labelled")
        assertEquals(true, resource("GET", "/api/v1/lifecycle").filtered, "holds nothing labelled")
        assertEquals(true, resource("GET", "/api/v1/freshness").filtered, "holds nothing labelled")
        assertEquals(false, resource("POST", "/api/v1/impact").filtered)
        assertEquals(true, resource("POST", "/graphql", document = "{ node(id: \"Team:x\") { id } }").filtered)
        assertEquals(false, resource("POST", "/graphql", document = "{ repositories { id } }").filtered)
    }
}
