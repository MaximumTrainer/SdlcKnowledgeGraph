package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.dto.EdgeListResponse
import com.repodatagraph.adapter.`in`.rest.dto.EdgeViewResponse
import com.repodatagraph.adapter.`in`.rest.dto.NeighbourhoodResponse
import com.repodatagraph.adapter.`in`.rest.dto.NodePageResponse
import com.repodatagraph.adapter.`in`.rest.dto.NodeRefResponse
import com.repodatagraph.adapter.`in`.rest.dto.NodeResponse
import com.repodatagraph.adapter.`in`.rest.dto.ProvenanceResponse
import com.repodatagraph.adapter.`in`.rest.dto.SubgraphEdgeResponse
import com.repodatagraph.adapter.`in`.rest.dto.SubgraphNodeResponse
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

/**
 * What the REST reads of nodes return, filtered by the policy (#30 FR4, FR5): a hidden type left
 * out, a property above the reader's clearance taken out and named. The shipped policy and its data
 * decide; a viewer is cleared for internal, so a Team's confidential email is redacted from them and
 * a restricted ServicePrincipal hidden.
 */
class PolicyResponseAdviceTest {
    private val advice = PolicyResponseAdvice()
    private val servletResponse = MockHttpServletResponse()

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signedIn(vararg claims: Pair<String, Any>) {
        val jwt =
            Jwt
                .withTokenValue("t")
                .header("alg", "RS256")
                .subject("vera")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .claims { it.putAll(mapOf("scope" to "graph:read") + claims) }
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    private fun viewer() = signedIn("sdlc_roles" to listOf("viewer"))

    private fun write(
        body: Any,
        request: MockHttpServletRequest = MockHttpServletRequest(),
    ): Any? =
        advice.beforeBodyWrite(
            body,
            mock(),
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter::class.java,
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(servletResponse),
        )

    private val provenance = Provenance(sourceSystem = "manual", ingestedAt = AT, validFrom = AT)

    private fun node(
        type: String,
        key: String,
        props: Map<String, Any?> = emptyMap(),
    ) = NodeResponse("$type:$key", type, key, props, ProvenanceResponse(provenance, false))

    private val team = node("Team", "platform", mapOf("name" to "Platform", "email" to "platform@acme.example"))
    private val principal = node("ServicePrincipal", "ci", mapOf("name" to "ci"))

    @Test
    fun `a caller without roles gets every read exactly as it was`() {
        signedIn()
        val page = NodePageResponse(listOf(team, principal), null)

        assertSame(page, write(page))
        assertSame(team, write(team))
    }

    @Test
    fun `a property above the reader's clearance is taken out and named`() {
        viewer()

        val written = write(team) as NodeResponse

        assertEquals(mapOf("name" to "Platform"), written.props)
        assertEquals(listOf("email"), written.redacted)
    }

    @Test
    fun `a node of a type above the reader's clearance is refused`() {
        viewer()

        val written = write(principal)

        assertEquals(403, servletResponse.status)
        assertEquals("policy denied", (written as Map<*, *>)["error"])
        assertEquals("sensitivity", written["policy"])
    }

    @Test
    fun `a page leaves out the nodes the reader may not see`() {
        viewer()

        val written = write(NodePageResponse(listOf(team, principal), "next")) as NodePageResponse

        assertEquals(listOf("Team:platform"), written.items.map { it.id })
        assertEquals("next", written.nextCursor)
    }

    @Test
    fun `relationships to a hidden node are left out, and all of them when the node asked about is hidden`() {
        viewer()
        val toTeam = edge(NodeRefResponse("Team:platform", "Team", "platform", mapOf("email" to "x")))
        val toPrincipal = edge(NodeRefResponse("ServicePrincipal:ci", "ServicePrincipal", "ci"))
        val list = EdgeListResponse(listOf(toTeam, toPrincipal))

        val ofRepository = write(list, MockHttpServletRequest().apply { setParameter("nodeId", "Repository:r") }) as EdgeListResponse
        val ofPrincipal = write(list, MockHttpServletRequest().apply { setParameter("nodeId", "ServicePrincipal:ci") }) as EdgeListResponse

        assertEquals(listOf("Team:platform"), ofRepository.items.map { it.other.id })
        assertEquals(
            emptyMap<String, Any?>(),
            ofRepository.items
                .single()
                .other.props,
        )
        assertEquals(emptyList<EdgeViewResponse>(), ofPrincipal.items)
    }

    @Test
    fun `a neighbourhood loses hidden nodes and the edges to them, and counts them`() {
        viewer()
        val view =
            NeighbourhoodResponse(
                root = "Team:platform",
                nodes = listOf(subgraphNode("Team:platform", "Team", 0), subgraphNode("ServicePrincipal:ci", "ServicePrincipal", 1)),
                edges =
                    listOf(
                        SubgraphEdgeResponse(
                            "OWNED_BY:ServicePrincipal:ci>Team:platform",
                            "OWNED_BY",
                            "OWNS",
                            "ServicePrincipal:ci",
                            "Team:platform",
                            1.0,
                            false,
                        ),
                    ),
                truncated = false,
            )

        val written = write(view) as NeighbourhoodResponse

        assertEquals(listOf("Team:platform"), written.nodes.map { it.id })
        assertEquals(emptyList<SubgraphEdgeResponse>(), written.edges)
        assertEquals(1, written.truncatedByPolicy)
    }

    private fun edge(other: NodeRefResponse) =
        EdgeViewResponse("OWNED_BY", "OWNS", "out", "OWNED_BY", other, emptyMap(), ProvenanceResponse(provenance, false))

    private fun subgraphNode(
        id: String,
        type: String,
        distance: Int,
    ) = SubgraphNodeResponse(id, type, id.substringAfter(':'), id, distance, emptyMap(), provenance)

    private companion object {
        val AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
