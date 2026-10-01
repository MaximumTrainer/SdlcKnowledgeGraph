package com.repodatagraph.adapter.`in`.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.policy.Decision
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * The refusals the authorisation policy gives that are not a missing scope (#95 FR-1), built here so
 * the gate and the application's own refusals cannot drift:
 *
 * ```
 * 403 {"error": "policy denied", "policy": "<the rule>", "reason": "<why>"}
 * 503 {"error": "policy_unavailable"}
 * ```
 */
object PolicyRefusal {
    const val DENIED = "policy denied"
    const val UNAVAILABLE = "policy_unavailable"

    val UNAVAILABLE_BODY: Map<String, String> = mapOf("error" to UNAVAILABLE)

    fun body(decision: Decision): Map<String, String> = mapOf("error" to DENIED, "policy" to decision.policy, "reason" to decision.reason)

    fun write(
        objectMapper: ObjectMapper,
        response: HttpServletResponse,
        decision: Decision,
    ) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, body(decision))
    }
}
