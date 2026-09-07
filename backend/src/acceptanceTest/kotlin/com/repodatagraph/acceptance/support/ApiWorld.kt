package com.repodatagraph.acceptance.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.spring.ScenarioScope
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * Per-scenario state shared by step definition classes: the last HTTP response, so that one class
 * can make a request and another can assert on it without redefining the same Gherkin step.
 */
@Component
@ScenarioScope
class ApiWorld(
    private val restTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
) {
    private var response: ResponseEntity<String>? = null

    fun get(path: String): ResponseEntity<String> = restTemplate.getForEntity(path, String::class.java).also { response = it }

    fun post(
        path: String,
        body: Any?,
    ): ResponseEntity<String> = restTemplate.postForEntity(path, body, String::class.java).also { response = it }

    fun put(
        path: String,
        body: Any?,
    ): ResponseEntity<String> = exchange(HttpMethod.PUT, path, body)

    fun delete(path: String): ResponseEntity<String> = exchange(HttpMethod.DELETE, path, null)

    /**
     * `TestRestTemplate` has no PUT or DELETE that returns a body, and both are needed here: an
     * update returns the node, and a refused delete returns why it was refused.
     */
    private fun exchange(
        method: HttpMethod,
        path: String,
        body: Any?,
    ): ResponseEntity<String> =
        restTemplate
            .exchange(path, method, body?.let { HttpEntity(it) }, String::class.java)
            .also { response = it }

    fun lastResponse(): ResponseEntity<String> = checkNotNull(response) { "No request has been made yet" }

    fun lastStatus(): Int = lastResponse().statusCode.value()

    fun lastBody(): JsonNode = objectMapper.readTree(lastResponse().body ?: "null")

    fun lastHeader(name: String): String? = lastResponse().headers.getFirst(name)
}
