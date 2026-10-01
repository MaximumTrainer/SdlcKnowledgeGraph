package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.exception.PolicyUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * A policy that could not be evaluated inside the application (#95, ADR-0020), past the gate - a
 * read being filtered, a source being checked, a question put to `/api/v1/policy` - is answered as
 * the gate answers it ([PolicyRefusal]): `503 policy_unavailable`, whether the request read or wrote.
 */
@RestControllerAdvice
class PolicyRefusalAdvice {
    @ExceptionHandler(PolicyUnavailableException::class)
    fun onUnavailable(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(PolicyRefusal.UNAVAILABLE_BODY)
}
