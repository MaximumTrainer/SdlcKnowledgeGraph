package com.repodatagraph.adapter.`in`.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * What each request needs (#116, FR-1 to FR-4), declared once per route family rather than on each
 * handler: GET under /api/v1 needs graph:read, anything that changes the graph graph:write, and a
 * short allowlist needs no graph scope at all.
 */
class ScopePolicyTest {
    @ParameterizedTest
    @CsvSource(
        "GET, /api/v1/nodes/Repository",
        "GET, /api/v1/nodes/Repository/github.com/acme/payments",
        "HEAD, /api/v1/nodes/Repository",
        "GET, /api/v1/edges",
        "GET, /api/v1/graph/repositories/r1/impact",
        "GET, /api/v1/graph/neighbourhood",
        "GET, /api/v1/work-items/deployments",
        "GET, /api/v1/deployments/work-items",
        "GET, /api/v1/connectors",
        "GET, /api/v1/sync-runs/42",
        "GET, /api/v1/service-principals",
    )
    fun `a read under the API needs graph read`(
        method: String,
        path: String,
    ) {
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.READ)), ScopePolicy.requirementFor(method, path))
    }

    @ParameterizedTest
    @CsvSource(
        "POST, /api/v1/nodes/Deployment",
        "PUT, /api/v1/nodes/Team/platform",
        "PATCH, /api/v1/nodes/Team/platform",
        "DELETE, /api/v1/nodes/Team/platform",
        "POST, /api/v1/edges",
        "DELETE, /api/v1/edges",
        "POST, /api/v1/connectors/github/sync",
        "POST, /api/v1/repositories/r1/teams/t1",
        "POST, /api/v1/service-principals",
        "DELETE, /api/v1/service-principals/triage-agent",
    )
    fun `a write under the API needs graph write`(
        method: String,
        path: String,
    ) {
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.WRITE)), ScopePolicy.requirementFor(method, path))
    }

    @Test
    fun `a query sent as a POST because its input is a body needs graph read, not graph write`() {
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.READ)), ScopePolicy.requirementFor("POST", "/api/v1/impact"))
    }

    @ParameterizedTest
    @CsvSource(
        // Only the method and the exact path named: anything else under it is a write as ever.
        "PUT, /api/v1/impact",
        "DELETE, /api/v1/impact",
        "POST, /api/v1/impact/extra",
    )
    fun `a read over POST covers only the method and the path it names`(
        method: String,
        path: String,
    ) {
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.WRITE)), ScopePolicy.requirementFor(method, path))
    }

    @ParameterizedTest
    @CsvSource(
        "GET, /api/v1/ontology",
        "GET, /api/v1/ontology/nodes/Repository",
        "POST, /api/v1/ingest/deployment",
        "POST, /api/v1/ingest/seed",
        "POST, /api/v1/webhooks/github",
        "GET, /actuator/health",
        "GET, /actuator/health/liveness",
        "GET, /actuator/info",
        "GET, /actuator/prometheus",
        "GET, /api-docs",
        "GET, /api-docs.yaml",
        "GET, /api-docs/swagger-config",
        "GET, /swagger-ui.html",
        "GET, /swagger-ui/index.html",
        "POST, /error",
    )
    fun `the public allowlist needs no graph scope`(
        method: String,
        path: String,
    ) {
        assertInstanceOf(RouteRequirement.Public::class.java, ScopePolicy.requirementFor(method, path))
    }

    @ParameterizedTest
    @CsvSource(
        // The ontology is public to read, not to write.
        "POST, /api/v1/ontology",
        // The ingest and webhook exemptions are for the method they serve.
        "GET, /api/v1/ingest/seed",
        "GET, /api/v1/webhooks/github",
        // One level of webhook, as the security configuration has always allowed.
        "POST, /api/v1/webhooks/github/extra",
    )
    fun `an exemption covers only the method and the paths it names`(
        method: String,
        path: String,
    ) {
        assertInstanceOf(RouteRequirement.Scopes::class.java, ScopePolicy.requirementFor(method, path))
    }

    @Test
    fun `GraphQL is decided by the operations in the document`() {
        assertEquals(RouteRequirement.ByGraphQlOperation, ScopePolicy.requirementFor("POST", "/graphql"))
        assertEquals(RouteRequirement.ByGraphQlOperation, ScopePolicy.requirementFor("GET", "/graphql"))
    }

    @Test
    fun `a path no family covers has no declared requirement`() {
        assertNull(ScopePolicy.requirementFor("GET", "/api/v2/nodes/Repository"))
        assertNull(ScopePolicy.requirementFor("GET", "/somewhere-else"))
    }

    @Test
    fun `a path spelled to slip past the families is still covered`() {
        assertEquals(RouteRequirement.Scopes(setOf(GraphScope.WRITE)), ScopePolicy.requirementFor("post", "/api/v1/nodes/Team"))
    }
}
