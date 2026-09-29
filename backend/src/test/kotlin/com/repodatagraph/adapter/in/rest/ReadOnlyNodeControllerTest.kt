package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.port.`in`.NodeUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP contract a read-only deployment offers the public (#48, D5): every write verb is refused
 * with 403 and a body saying why, before the request reaches a use case, and reads are untouched.
 */
@WebMvcTest(NodeController::class, properties = ["sdlc.read-only=true"])
@Import(NodeRestExceptionHandler::class)
class ReadOnlyNodeControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var nodeUseCase: NodeUseCase

    @ParameterizedTest
    @CsvSource(
        textBlock = """
            POST,   /api/v1/nodes/Team
            PUT,    /api/v1/nodes/Team/platform
            PATCH,  /api/v1/nodes/Team/platform
            DELETE, /api/v1/nodes/Team/platform""",
    )
    fun `every write verb is refused before it reaches the use case`(
        verb: String,
        path: String,
    ) {
        mockMvc
            .perform(
                request(HttpMethod.valueOf(verb), path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"props":{"name":"platform"}}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("this instance is read-only"))

        verifyNoInteractions(nodeUseCase)
    }

    @Test
    fun `reading is unaffected`() {
        whenever(nodeUseCase.list("Team", 50, null)).thenReturn(NodePage(emptyList(), null))

        mockMvc.perform(get("/api/v1/nodes/Team")).andExpect(status().isOk)
    }
}
