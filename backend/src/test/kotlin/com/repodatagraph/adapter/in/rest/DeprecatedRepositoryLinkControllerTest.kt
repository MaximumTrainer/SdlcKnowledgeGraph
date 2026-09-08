package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.model.EdgeWrite
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The superseded link endpoints.
 *
 * What is asserted is that they are now a thin front onto the edge use case rather than a second
 * write path, and that they say so in their headers. The ids are slash-free on purpose: a Repository
 * id cannot sit in a mid-path segment, which is why these are unreachable for real repositories.
 */
@WebMvcTest(DeprecatedRepositoryLinkController::class)
class DeprecatedRepositoryLinkControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var edgeUseCase: EdgeUseCase

    private fun written(
        type: String,
        inverse: String,
        props: Map<String, Any?> = emptyMap(),
    ) = EdgeWrite(
        GraphEdge(type, NodeKey("Repository", "r1"), NodeKey("Team", "platform"), props, Provenance.manual()),
        inverse = inverse,
        created = true,
    )

    @Test
    fun `linking to a team goes through the edge use case and says it is deprecated`() {
        whenever(edgeUseCase.create(any())).thenReturn(written("OWNED_BY", "OWNS"))

        mockMvc
            .perform(post("/api/v1/repositories/r1/teams/platform"))
            .andExpect(status().isOk)
            .andExpect(header().string("Deprecation", "true"))
            .andExpect(header().string("Link", """</api/v1/edges>; rel="successor-version""""))
    }

    @Test
    fun `a bare key is qualified with its type before it reaches the edge API`() {
        whenever(edgeUseCase.create(any())).thenReturn(written("OWNED_BY", "OWNS"))

        mockMvc.perform(post("/api/v1/repositories/r1/teams/platform")).andExpect(status().isOk)

        val request = argumentCaptor<EdgeRequest>()
        verify(edgeUseCase).create(request.capture())
        assertThat(request.firstValue.fromId).isEqualTo("Repository:r1")
        assertThat(request.firstValue.toId).isEqualTo("Team:platform")
    }

    @Test
    fun `adding a dependency states the kind the edge type now requires`() {
        whenever(edgeUseCase.create(any())).thenReturn(written("DEPENDS_ON", "DEPENDED_ON_BY", mapOf("kind" to "api")))

        mockMvc
            .perform(post("/api/v1/repositories/r1/dependencies/r2").param("kind", "api"))
            .andExpect(status().isOk)

        val request = argumentCaptor<EdgeRequest>()
        verify(edgeUseCase).create(request.capture())
        assertThat(request.firstValue.props["kind"]).isEqualTo("api")
    }

    @Test
    fun `an old caller that says nothing gets the commonest kind rather than a refusal`() {
        whenever(edgeUseCase.create(any())).thenReturn(written("DEPENDS_ON", "DEPENDED_ON_BY", mapOf("kind" to "library")))

        mockMvc.perform(post("/api/v1/repositories/r1/dependencies/r2")).andExpect(status().isOk)

        val request = argumentCaptor<EdgeRequest>()
        verify(edgeUseCase).create(request.capture())
        assertThat(request.firstValue.props["kind"]).isEqualTo("library")
    }
}
