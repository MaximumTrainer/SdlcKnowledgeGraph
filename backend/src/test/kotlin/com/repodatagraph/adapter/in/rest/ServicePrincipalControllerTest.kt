package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.ServicePrincipalExistsException
import com.repodatagraph.domain.exception.ServicePrincipalNotFoundException
import com.repodatagraph.domain.exception.ServicePrincipalValidationException
import com.repodatagraph.domain.exception.UnknownOwningTeamException
import com.repodatagraph.domain.exception.UserPrincipalRequiredException
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalRegistration
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The service principal registry over HTTP (#115, FR-2 and FR-4): register, list, deregister, and the
 * shape of each refusal. Who may register - a user, never a service - is the use case's rule; this
 * pins the 403 it becomes.
 */
@WebMvcTest(ServicePrincipalController::class)
class ServicePrincipalControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: ServicePrincipalUseCase

    private val registeredAt = Instant.parse("2026-09-30T09:00:00Z")

    private val triage =
        ServicePrincipal(
            name = "triage-agent",
            ownedBy = "team-payments",
            description = "Triages incidents",
            registeredBy = "dan",
            validFrom = registeredAt,
        )

    private fun register(body: String) = post("/api/v1/service-principals").contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `POST registers a principal and answers 201 with it and where it lives`() {
        whenever(useCase.register(ServicePrincipalRegistration("triage-agent", "team-payments", "Triages incidents")))
            .thenReturn(triage)

        mockMvc
            .perform(register("""{"name":"triage-agent","ownedBy":"team-payments","description":"Triages incidents"}"""))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/v1/service-principals/triage-agent"))
            .andExpect(jsonPath("$.name").value("triage-agent"))
            .andExpect(jsonPath("$.ownedBy").value("team-payments"))
            .andExpect(jsonPath("$.description").value("Triages incidents"))
            .andExpect(jsonPath("$.registeredBy").value("dan"))
            .andExpect(jsonPath("$.validFrom").value("2026-09-30T09:00:00Z"))
            .andExpect(jsonPath("$.validTo").doesNotExist())
    }

    @Test
    fun `the description is optional`() {
        whenever(useCase.register(ServicePrincipalRegistration("triage-agent", "team-payments", null)))
            .thenReturn(triage.copy(description = null))

        mockMvc
            .perform(register("""{"name":"triage-agent","ownedBy":"team-payments"}"""))
            .andExpect(status().isCreated)
    }

    @Test
    fun `a registration without a name or an owner is a 400 naming the field`() {
        mockMvc
            .perform(register("""{"ownedBy":"team-payments"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid service principal"))
            .andExpect(jsonPath("$.field").value("name"))

        mockMvc
            .perform(register("""{"name":"triage-agent"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("ownedBy"))
    }

    @Test
    fun `a name the use case refuses is a 400 naming the field`() {
        whenever(useCase.register(any())).thenThrow(ServicePrincipalValidationException("name", "must be a client id"))

        mockMvc
            .perform(register("""{"name":"not/a client","ownedBy":"team-payments"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid service principal"))
            .andExpect(jsonPath("$.field").value("name"))
            .andExpect(jsonPath("$.message").value("must be a client id"))
    }

    @Test
    fun `an owner the graph has no team for is a 400 naming it`() {
        whenever(useCase.register(any())).thenThrow(UnknownOwningTeamException("team-nobody"))

        mockMvc
            .perform(register("""{"name":"triage-agent","ownedBy":"team-nobody"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unknown team"))
            .andExpect(jsonPath("$.ownedBy").value("team-nobody"))
    }

    @Test
    fun `a name already registered is a 409`() {
        whenever(useCase.register(any())).thenThrow(ServicePrincipalExistsException("triage-agent"))

        mockMvc
            .perform(register("""{"name":"triage-agent","ownedBy":"team-payments"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("service principal exists"))
            .andExpect(jsonPath("$.name").value("triage-agent"))
    }

    @Test
    fun `a service that tries to register is a 403`() {
        whenever(useCase.register(any())).thenThrow(UserPrincipalRequiredException())

        mockMvc
            .perform(register("""{"name":"github-connector","ownedBy":"team-payments"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("only a user may manage service principals"))
    }

    @Test
    fun `GET lists every registration, deregistered ones included`() {
        val gone = triage.copy(name = "old-agent", validTo = registeredAt.plusSeconds(60))
        whenever(useCase.list()).thenReturn(listOf(gone, triage))

        mockMvc
            .perform(get("/api/v1/service-principals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].name").value("old-agent"))
            .andExpect(jsonPath("$.items[0].validTo").value("2026-09-30T09:01:00Z"))
            .andExpect(jsonPath("$.items[1].name").value("triage-agent"))
    }

    @Test
    fun `DELETE deregisters, answering with the record and its validTo`() {
        whenever(useCase.deregister("triage-agent")).thenReturn(triage.copy(validTo = registeredAt.plusSeconds(60)))

        mockMvc
            .perform(delete("/api/v1/service-principals/triage-agent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("triage-agent"))
            .andExpect(jsonPath("$.validTo").value("2026-09-30T09:01:00Z"))

        verify(useCase).deregister("triage-agent")
    }

    @Test
    fun `deregistering a name never registered is a 404`() {
        whenever(useCase.deregister("nobody")).thenThrow(ServicePrincipalNotFoundException("nobody"))

        mockMvc
            .perform(delete("/api/v1/service-principals/nobody"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("service principal not found"))
            .andExpect(jsonPath("$.name").value("nobody"))
    }

    @Test
    fun `a service that tries to deregister is a 403`() {
        whenever(useCase.deregister("triage-agent")).thenThrow(UserPrincipalRequiredException())

        mockMvc
            .perform(delete("/api/v1/service-principals/triage-agent"))
            .andExpect(status().isForbidden)
    }
}
