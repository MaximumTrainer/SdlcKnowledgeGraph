package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.port.`in`.SeedIngestOutcome
import com.repodatagraph.domain.port.`in`.SeedIngestUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP contract of `POST /api/v1/ingest/seed` (#47, FR10): the same four outcomes and statuses as
 * the deployment ingest endpoint, and the body passed on byte for byte with the Authorization header.
 */
@WebMvcTest(SeedIngestController::class)
class SeedIngestControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: SeedIngestUseCase

    private val body = """{"nodes":[{"type":"Team","props":{"name":"platform"}}]}"""

    private fun seed(authorization: String? = "Bearer t") =
        mockMvc.perform(
            post("/api/v1/ingest/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .apply { authorization?.let { header("Authorization", it) } },
        )

    @Test
    fun `an accepted seed answers 202 with what it asserted`() {
        whenever(useCase.seed(anyOrNull(), any())).thenReturn(SeedIngestOutcome.Accepted(created = true, nodes = 3, edges = 2))

        seed()
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.nodes").value(3))
            .andExpect(jsonPath("$.edges").value(2))

        verify(useCase).seed(eq("Bearer t"), eq(body.toByteArray()))
    }

    @Test
    fun `a seed without a valid token answers 401`() {
        whenever(useCase.seed(anyOrNull(), any())).thenReturn(SeedIngestOutcome.Unauthorized)

        seed(authorization = null)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("a valid bearer token is required"))
    }

    @Test
    fun `an invalid seed answers 400 naming each field`() {
        whenever(useCase.seed(anyOrNull(), any())).thenReturn(
            SeedIngestOutcome.Invalid(mapOf("nodes[0].type" to "Deployment cannot be seeded")),
        )

        seed()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid seed"))
            .andExpect(jsonPath("$.fields['nodes[0].type']").value("Deployment cannot be seeded"))
    }

    @Test
    fun `an instance with no ingest token configured answers 503`() {
        whenever(useCase.seed(anyOrNull(), any())).thenReturn(SeedIngestOutcome.Disabled)

        seed()
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("seed ingest is not configured on this instance"))
    }
}
