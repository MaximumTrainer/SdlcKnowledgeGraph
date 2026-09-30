package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.exception.SourceNotPermittedException
import com.repodatagraph.observability.LogEvents
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * A write naming a source system its token holds no `graph:write:<source>` scope for (#117) is
 * refused exactly as a request missing `graph:write` is (#116): the same body, challenge and
 * security event, from [InsufficientScope]. A caller handles one kind of refusal, whichever scope
 * it lacked.
 *
 * The source check runs in the application, after the request is let in, because the source is in
 * the body; the refusal still comes before anything is written.
 */
@RestControllerAdvice
class ScopeRefusalAdvice {
    @ExceptionHandler(SourceNotPermittedException::class)
    fun onSourceNotPermitted(
        exception: SourceNotPermittedException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val principal = InsufficientScope.principalOf(SecurityContextHolder.getContext().authentication)
        LogEvents.scopeRefused(principal, request.method, exception.required.sorted(), exception.held.sorted())
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .header(InsufficientScope.CHALLENGE_HEADER, InsufficientScope.challenge(exception.required))
            .body(InsufficientScope.body(exception.required, exception.held))
    }
}
