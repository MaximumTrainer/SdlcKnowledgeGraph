package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(RepositoryController::class)
class RepositoryControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var repositoryUseCase: RepositoryUseCase

    @Test
    fun `POST repositories registers and returns 201`() {
        val request = CreateRepositoryRequest(orgRepo = "org/repo")
        val saved = Repository(id = "1", orgRepo = "org/repo")
        whenever(repositoryUseCase.registerRepository(any())).thenReturn(saved)

        mockMvc
            .perform(
                post("/api/v1/repositories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.orgRepo").value("org/repo"))
    }

    @Test
    fun `GET repositories returns list`() {
        whenever(repositoryUseCase.listRepositories()).thenReturn(
            listOf(Repository(id = "1", orgRepo = "org/repo")),
        )

        mockMvc
            .perform(get("/api/v1/repositories"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].orgRepo").value("org/repo"))
    }

    @Test
    fun `GET repository by id returns 200`() {
        whenever(repositoryUseCase.getRepository("1")).thenReturn(
            Repository(id = "1", orgRepo = "org/repo"),
        )

        mockMvc
            .perform(get("/api/v1/repositories/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("1"))
    }

    @Test
    fun `GET repository by id returns 404 when not found`() {
        whenever(repositoryUseCase.getRepository("999")).thenReturn(null)

        mockMvc
            .perform(get("/api/v1/repositories/999"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE repository returns 204`() {
        mockMvc
            .perform(delete("/api/v1/repositories/1"))
            .andExpect(status().isNoContent)
        verify(repositoryUseCase).deleteRepository("1")
    }
}
