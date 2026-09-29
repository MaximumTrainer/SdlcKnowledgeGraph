package com.repodatagraph.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Gives every request a correlation id (#44, FR5): the caller's `X-Request-Id` when it is safe to write
 * into a log, a fresh one otherwise. The id is in the MDC for the whole request, so every line logged
 * while answering it carries it, and it is echoed in the response so a caller can quote it.
 *
 * The caller's value is untrusted and ends up on every log line, so it has to match
 * [SAFE_REQUEST_ID]: a newline or a quote in it would let a caller forge log entries, and an
 * unbounded one would let them fill the log.
 *
 * First of all the filters, so that a request refused before it reaches a controller - by the
 * read-only guard, say - still has an id. The MDC is cleared in a `finally`, because a pooled thread
 * that kept it would stamp the next request with this one's id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.takeIf { SAFE_REQUEST_ID.matches(it) } ?: UUID.randomUUID().toString()
        response.setHeader(HEADER, requestId)
        MDC.put(MDC_KEY, requestId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
