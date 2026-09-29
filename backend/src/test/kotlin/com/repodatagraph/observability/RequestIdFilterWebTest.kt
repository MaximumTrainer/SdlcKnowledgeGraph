package com.repodatagraph.observability

import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The HTTP contract of request correlation (#44, FR5): an `X-Request-Id` a log can safely carry is
 * used and echoed, anything else is replaced, and the id is what a controller sees in the MDC.
 */
@WebMvcTest(RequestIdFilterWebTest.EchoController::class)
@Import(RequestIdFilterWebTest.EchoController::class)
class RequestIdFilterWebTest {
    @RestController
    class EchoController {
        @GetMapping("/echo")
        fun echo(): String = MDC.get("requestId") ?: "none"
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `a safe id is used for the request and echoed`() {
        mockMvc
            .perform(get("/echo").header("X-Request-Id", "abc-123"))
            .andExpect(header().string("X-Request-Id", "abc-123"))
            .andExpect(content().string("abc-123"))
    }

    @Test
    fun `a request without an id is given one, the same in the log and in the answer`() {
        val result = mockMvc.perform(get("/echo")).andReturn()

        val issued = result.response.getHeader("X-Request-Id")
        assert(issued != null && issued.matches(Regex(SAFE))) { "issued $issued" }
        assert(result.response.contentAsString == issued)
    }

    @Test
    fun `an id that would let a caller forge log lines is replaced`() {
        val forged = "a\nlevel=ERROR forged=true"

        mockMvc
            .perform(get("/echo").header("X-Request-Id", forged))
            .andExpect(header().string("X-Request-Id", matchesPattern(SAFE)))
            .andExpect(header().string("X-Request-Id", not(forged)))
    }

    private companion object {
        const val SAFE = "^[A-Za-z0-9._-]{1,64}$"
    }
}
