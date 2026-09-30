package com.repodatagraph.adapter.`in`.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which scopes a write naming a source needs (#117, FR-2). */
class SourceScopesTest {
    @Test
    fun `manual needs graph write alone`() {
        assertEquals(listOf("graph:write"), SourceScopes.requiredFor("manual"))
    }

    @Test
    fun `any other source needs its own write scope on top, sorted`() {
        assertEquals(listOf("graph:write", "graph:write:github"), SourceScopes.requiredFor("github"))
        assertEquals(listOf("graph:write", "graph:write:github-actions"), SourceScopes.requiredFor("github-actions"))
        assertEquals(listOf("graph:write", "graph:write:aws"), SourceScopes.requiredFor("aws"))
    }

    @Test
    fun `a source's scope is graph write and its name`() {
        assertEquals("graph:write:servicenow", SourceScopes.scopeFor("servicenow"))
    }
}
