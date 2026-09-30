package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.SourceLag
import com.repodatagraph.domain.port.`in`.SourceFreshnessUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * How far behind each source is (#93, FR-3 and FR-6): the same answer the `freshness` health
 * component gives, as a read the web interface can make, since it proxies no health details.
 */
@WebMvcTest(FreshnessController::class)
class FreshnessControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var sourceFreshness: SourceFreshnessUseCase

    @Test
    fun `each source's lag is listed against its window`() {
        whenever(sourceFreshness.lag()).thenReturn(
            listOf(
                SourceLag(
                    source = "github",
                    window = Duration.ofHours(24),
                    lastSuccessAt = Instant.parse("2026-09-29T06:00:00Z"),
                    lag = Duration.ofHours(30),
                    lagging = true,
                ),
                SourceLag(source = "aws", window = Duration.ofHours(6), lastSuccessAt = null, lag = null, lagging = false),
            ),
        )

        mockMvc
            .perform(get("/api/v1/freshness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sources.length()").value(2))
            .andExpect(jsonPath("$.sources[0].source").value("github"))
            .andExpect(jsonPath("$.sources[0].window").value("PT24H"))
            .andExpect(jsonPath("$.sources[0].windowSeconds").value(86400))
            .andExpect(jsonPath("$.sources[0].lastSuccessAt").value("2026-09-29T06:00:00Z"))
            .andExpect(jsonPath("$.sources[0].lagSeconds").value(108000))
            .andExpect(jsonPath("$.sources[0].lagging").value(true))
            .andExpect(jsonPath("$.sources[1].source").value("aws"))
            .andExpect(jsonPath("$.sources[1].lastSuccessAt").doesNotExist())
            .andExpect(jsonPath("$.sources[1].lagSeconds").doesNotExist())
            .andExpect(jsonPath("$.sources[1].lagging").value(false))
    }

    @Test
    fun `no source to report is an empty list, not an error`() {
        whenever(sourceFreshness.lag()).thenReturn(emptyList())

        mockMvc
            .perform(get("/api/v1/freshness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sources.length()").value(0))
    }
}
