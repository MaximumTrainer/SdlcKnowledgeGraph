package com.repodatagraph.observability

import com.repodatagraph.config.ReadOnlyGuard
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The request id is untrusted input that ends up on every log line (#44, FR5), so it is checked,
 * and it must not outlive its request on a pooled thread.
 */
class RequestIdFilterTest {
    private val filter = RequestIdFilter()

    private fun run(
        requestId: String?,
        chain: FilterChain = FilterChain { _, _ -> },
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/api/v1/ontology").apply { requestId?.let { addHeader("X-Request-Id", it) } }
        return MockHttpServletResponse().also { filter.doFilter(request, it, chain) }
    }

    @Test
    fun `puts the id in the MDC for the request and takes it out afterwards`() {
        var seen: String? = null

        run("abc-123", { _, _ -> seen = MDC.get("requestId") })

        assertThat(seen).isEqualTo("abc-123")
        assertThat(MDC.get("requestId")).isNull()
    }

    @Test
    fun `takes the id out even when the request fails`() {
        assertThatThrownBy { run("abc-123", { _, _ -> throw IllegalStateException("boom") }) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(MDC.get("requestId")).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["a b", "a\nb", "a\rb", "{\"x\":1}", "", "a/b", "é"])
    fun `replaces an id that is not safe to write into a log`(hostile: String) {
        val issued = run(hostile).getHeader("X-Request-Id")

        assertThat(issued).isNotEqualTo(hostile).matches(SAFE)
    }

    @Test
    fun `replaces an id longer than 64 characters`() {
        assertThat(run("a".repeat(65)).getHeader("X-Request-Id")).hasSizeLessThanOrEqualTo(64).isNotEqualTo("a".repeat(65))
        assertThat(run("a".repeat(64)).getHeader("X-Request-Id")).isEqualTo("a".repeat(64))
    }

    @Test
    fun `gives every request without an id a different one`() {
        assertThat(run(null).getHeader("X-Request-Id")).matches(SAFE).isNotEqualTo(run(null).getHeader("X-Request-Id"))
    }

    @Test
    fun `runs before the read-only guard, so a refused request still has an id`() {
        val order = { type: Class<*> -> AnnotationUtils.findAnnotation(type, Order::class.java)?.value ?: Ordered.LOWEST_PRECEDENCE }

        assertThat(order(RequestIdFilter::class.java)).isLessThan(order(ReadOnlyGuard::class.java))
    }

    private companion object {
        const val SAFE = "^[A-Za-z0-9._-]{1,64}$"
    }
}
