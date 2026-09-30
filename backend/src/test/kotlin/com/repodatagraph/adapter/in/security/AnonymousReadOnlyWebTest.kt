package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.ChangeImpactController
import com.repodatagraph.adapter.`in`.rest.NodeController
import com.repodatagraph.adapter.`in`.rest.NodeRestExceptionHandler
import com.repodatagraph.adapter.`in`.rest.SeedIngestController
import com.repodatagraph.adapter.`in`.rest.ServicePrincipalController
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.port.`in`.ChangeImpactUseCase
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.SeedIngestOutcome
import com.repodatagraph.domain.port.`in`.SeedIngestUseCase
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP contract of the anonymous read-only mode (#118): an instance with no identity provider,
 * which the API only lets start read-only (#48, FR6). This is how the dogfood instance runs.
 *
 * A read needs no token, because there is nobody to issue one. A write is refused as read-only
 * whether or not it carries a token, so no fact can be written by nobody. The ingest endpoints keep
 * their own token, as they do everywhere (docs/DEPLOYMENT.md, D6). And no token is ever decoded: with
 * no issuer there are no keys to trust, so the API holds no decoder at all.
 */
@WebMvcTest(
    controllers = [NodeController::class, SeedIngestController::class, ServicePrincipalController::class, ChangeImpactController::class],
    properties = [
        "sdlc.read-only=true",
        "sdlc.auth.issuer-uri=",
    ],
)
@Import(SecurityConfig::class, NodeRestExceptionHandler::class)
class AnonymousReadOnlyWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var context: ApplicationContext

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @MockitoBean
    private lateinit var seedIngestUseCase: SeedIngestUseCase

    @MockitoBean
    private lateinit var servicePrincipals: ServicePrincipalUseCase

    @MockitoBean
    private lateinit var changeImpact: ChangeImpactUseCase

    @Test
    fun `a read needs no token`() {
        whenever(nodeUseCase.list(eq("Repository"), any(), anyOrNull())).thenReturn(NodePage(emptyList(), null))

        mockMvc
            .perform(get("/api/v1/nodes/Repository"))
            .andExpect(status().isOk)
    }

    @Test
    fun `a query sent as a POST needs no token either, because it only reads (#87)`() {
        whenever(changeImpact.changeImpact(any())).thenThrow(NodeNotFoundException(listOf(NodeKey("Repository", "github.com/acme/x"))))

        mockMvc
            .perform(post("/api/v1/impact").contentType(MediaType.APPLICATION_JSON).content("""{"repositoryKey":"github.com/acme/x"}"""))
            .andExpect(status().isNotFound)

        verify(changeImpact).changeImpact(any())
    }

    @Test
    fun `a write without a token is refused as read-only, not as unauthenticated`() {
        mockMvc
            .perform(post("/api/v1/nodes/Team").contentType(MediaType.APPLICATION_JSON).content("""{"props":{"name":"platform"}}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("this instance is read-only"))

        verifyNoInteractions(nodeUseCase)
    }

    @Test
    fun `a write carrying a token is refused the same way, the token unread`() {
        mockMvc
            .perform(
                post("/api/v1/nodes/Team")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"props":{"name":"platform"}}""")
                    .header("Authorization", "Bearer anything"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("this instance is read-only"))

        verifyNoInteractions(nodeUseCase)
    }

    @Test
    fun `the service principal registry cannot be written`() {
        mockMvc
            .perform(
                post("/api/v1/service-principals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"triage-agent","ownedBy":"team-payments"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("this instance is read-only"))

        verifyNoInteractions(servicePrincipals)
    }

    @Test
    fun `the ingest endpoints keep their own token`() {
        whenever(seedIngestUseCase.seed(anyOrNull(), any())).thenReturn(SeedIngestOutcome.Accepted(created = true, nodes = 1, edges = 0))

        mockMvc
            .perform(
                post("/api/v1/ingest/seed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nodes":[]}""")
                    .header("Authorization", "Bearer ingest-token"),
            ).andExpect(status().isAccepted)

        verify(seedIngestUseCase).seed(eq("Bearer ingest-token"), any())
    }

    @Test
    fun `no token decoder exists to be fooled`() {
        assertNull(context.getBeanProvider(JwtDecoder::class.java).ifAvailable)
    }
}
