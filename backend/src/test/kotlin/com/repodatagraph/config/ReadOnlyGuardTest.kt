package com.repodatagraph.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ReadOnlyGuardTest {
    private val objectMapper = ObjectMapper()

    private fun guard(
        readOnly: Boolean = true,
        allowlist: Set<AllowedWrite> = emptySet(),
    ) = ReadOnlyGuard(readOnly, allowlist, objectMapper)

    private class Outcome(
        val chain: MockFilterChain,
        val response: MockHttpServletResponse,
    ) {
        val passed get() = chain.request != null
    }

    private fun ReadOnlyGuard.handle(request: MockHttpServletRequest): Outcome {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        doFilter(request, response, chain)
        return Outcome(chain, response)
    }

    private fun graphql(body: String) =
        MockHttpServletRequest("POST", "/graphql").apply {
            contentType = "application/json"
            setContent(body.toByteArray())
        }

    private fun assertRefused(outcome: Outcome) {
        assertNull(outcome.chain.request, "the request reached the application")
        assertEquals(403, outcome.response.status)
        assertEquals("this instance is read-only", objectMapper.readTree(outcome.response.contentAsString).path("error").asText())
    }

    @Test
    fun `a writable instance lets every write through`() {
        val outcome = guard(readOnly = false).handle(MockHttpServletRequest("DELETE", "/api/v1/nodes/Team/platform"))

        assertNotNull(outcome.chain.request)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "PATCH", "DELETE"])
    fun `every write verb is refused`(verb: String) {
        assertRefused(guard().handle(MockHttpServletRequest(verb, "/api/v1/nodes/Team")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET", "HEAD", "OPTIONS"])
    fun `reads are untouched`(verb: String) {
        assert(guard().handle(MockHttpServletRequest(verb, "/api/v1/nodes/Team")).passed)
    }

    @Test
    fun `a write to a path nobody has listed is refused, whatever the path`() {
        assertRefused(guard().handle(MockHttpServletRequest("POST", "/api/v1/something-added-next-year")))
        assertRefused(guard().handle(MockHttpServletRequest("POST", "/not-even-under-the-api")))
    }

    @Test
    fun `a write the allowlist names is let through, and only with the listed verb`() {
        val guard = guard(allowlist = setOf(AllowedWrite("POST", "/api/v1/ingest/deployment")))

        assert(guard.handle(MockHttpServletRequest("POST", "/api/v1/ingest/deployment")).passed)
        assertRefused(guard.handle(MockHttpServletRequest("DELETE", "/api/v1/ingest/deployment")))
        assertRefused(guard.handle(MockHttpServletRequest("POST", "/api/v1/ingest/deployment/extra")))
    }

    @Test
    fun `a query sent as a POST is answered, because it only reads (#87)`() {
        assert(guard().handle(MockHttpServletRequest("POST", "/api/v1/impact")).passed)
        assertRefused(guard().handle(MockHttpServletRequest("PUT", "/api/v1/impact")))
        assertRefused(guard().handle(MockHttpServletRequest("POST", "/api/v1/impact/extra")))
    }

    @Test
    fun `a context pack is answered too, because it only reads (#96)`() {
        assert(guard().handle(MockHttpServletRequest("POST", "/api/v1/context-pack")).passed)
        assertRefused(guard().handle(MockHttpServletRequest("PUT", "/api/v1/context-pack")))
        assertRefused(guard().handle(MockHttpServletRequest("POST", "/api/v1/context-pack/extra")))
    }

    @Test
    fun `a websocket upgrade is refused, because a GraphQL mutation can travel over one`() {
        val upgrade = MockHttpServletRequest("GET", "/graphql").apply { addHeader("Upgrade", "websocket") }

        assertRefused(guard().handle(upgrade))
    }

    @Test
    fun `an HTTP-2 upgrade offer on a read is not mistaken for a WebSocket`() {
        val read = MockHttpServletRequest("GET", "/api/v1/nodes/Team").apply { addHeader("Upgrade", "h2c") }

        assert(guard().handle(read).passed)
    }

    @Test
    fun `GraphiQL is not served`() {
        val outcome = guard().handle(MockHttpServletRequest("GET", "/graphiql"))

        assertNull(outcome.chain.request)
        assertEquals(404, outcome.response.status)
    }

    @Nested
    inner class GraphQl {
        @Test
        fun `a query is answered`() {
            val outcome = guard().handle(graphql("""{"query":"{ repositories { id } }"}"""))

            assert(outcome.passed)
            // The body was consumed to inspect it, so the application must still be able to read it.
            assertEquals(
                """{"query":"{ repositories { id } }"}""",
                outcome.chain.request!!
                    .inputStream
                    .readAllBytes()
                    .decodeToString(),
            )
        }

        @Test
        fun `a mutation is refused`() {
            assertRefused(guard().handle(graphql("""{"query":"mutation { deleteRepository(id: \"x\") }"}""")))
        }

        @Test
        fun `a document carrying a mutation beside a query is refused, whichever operation is named`() {
            val body = """{"query":"query Q { repositories { id } } mutation M { deleteRepository(id: \"x\") }","operationName":"Q"}"""

            assertRefused(guard().handle(graphql(body)))
        }

        @ParameterizedTest
        @ValueSource(strings = ["not json", "[]", """{"variables":{}}""", """{"query":"{ unclosed"}"""])
        fun `a request that cannot be shown to be free of mutations is refused`(body: String) {
            assertRefused(guard().handle(graphql(body)))
        }

        @Test
        fun `a body too large to inspect is refused`() {
            val huge = """{"query":"{ repositories { id } }","padding":"${"x".repeat(ReadOnlyGuard.MAX_INSPECTED_BODY_BYTES)}"}"""

            assertRefused(guard().handle(graphql(huge)))
        }

        @Test
        fun `a mutation sent as a GET query parameter is refused`() {
            val get = MockHttpServletRequest("GET", "/graphql").apply { addParameter("query", "mutation { deleteRepository(id: \"x\") }") }

            assertRefused(guard().handle(get))
        }
    }
}
