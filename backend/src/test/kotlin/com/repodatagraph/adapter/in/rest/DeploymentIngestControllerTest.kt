package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.port.`in`.DeploymentIngestOutcome
import com.repodatagraph.domain.port.`in`.DeploymentIngestUseCase
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
 * The HTTP contract of `POST /api/v1/ingest/deployment` (#7, FR5): four outcomes, four statuses, and
 * the body passed on byte for byte with the Authorization header, because the token is checked against
 * exactly what was sent.
 */
@WebMvcTest(DeploymentIngestController::class)
class DeploymentIngestControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: DeploymentIngestUseCase

    private val body = """{"repository":"github.com/acme/payments"}"""

    private fun ingest(authorization: String? = "Bearer t") =
        mockMvc.perform(
            post("/api/v1/ingest/deployment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .apply { authorization?.let { header("Authorization", it) } },
        )

    @Test
    fun `an accepted report answers 202 with what it recorded`() {
        whenever(useCase.ingest(anyOrNull(), any())).thenReturn(
            DeploymentIngestOutcome.Accepted(
                deploymentIds = listOf("ghcr.io/acme/api@sha256:abc#staging#1790000000"),
                created = true,
                nodes = 5,
                edges = 4,
            ),
        )

        ingest()
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.deploymentIds[0]").value("ghcr.io/acme/api@sha256:abc#staging#1790000000"))
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.nodes").value(5))
            .andExpect(jsonPath("$.edges").value(4))

        verify(useCase).ingest(eq("Bearer t"), eq(body.toByteArray()))
    }

    @Test
    fun `a report without a valid token answers 401`() {
        whenever(useCase.ingest(anyOrNull(), any())).thenReturn(DeploymentIngestOutcome.Unauthorized)

        ingest(authorization = null)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("a valid bearer token is required"))
    }

    @Test
    fun `an invalid report answers 400 naming each field`() {
        whenever(useCase.ingest(anyOrNull(), any())).thenReturn(
            DeploymentIngestOutcome.Invalid(mapOf("artifacts" to "artifacts must not be empty")),
        )

        ingest()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid deployment report"))
            .andExpect(jsonPath("$.fields.artifacts").value("artifacts must not be empty"))
    }

    @Test
    fun `an instance with no ingest token configured answers 503`() {
        whenever(useCase.ingest(anyOrNull(), any())).thenReturn(DeploymentIngestOutcome.Disabled)

        ingest()
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("deployment ingest is not configured on this instance"))
    }
}
