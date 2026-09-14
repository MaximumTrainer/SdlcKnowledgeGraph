package com.repodatagraph.acceptance.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.spring.ScenarioScope
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
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

    /**
     * A POST whose body is sent exactly as given, with extra headers.
     *
     * A webhook is verified over the raw bytes, so anything that re-serialises the body - which is
     * what `post` does - would change what the signature is computed against and make every
     * signature test pass or fail for the wrong reason.
     */
    fun postSigned(
        path: String,
        body: String,
        headers: Map<String, String>,
    ): ResponseEntity<String> {
        val httpHeaders = HttpHeaders()
        headers.forEach { (name, value) -> httpHeaders.add(name, value) }
        httpHeaders.contentType = MediaType.APPLICATION_JSON
        return restTemplate
            .exchange(path, HttpMethod.POST, HttpEntity(body, httpHeaders), String::class.java)
            .also { response = it }
    }

    fun lastResponse(): ResponseEntity<String> = checkNotNull(response) { "No request has been made yet" }

    fun lastStatus(): Int = lastResponse().statusCode.value()

    fun lastBody(): JsonNode = objectMapper.readTree(lastResponse().body ?: "null")

    fun lastHeader(name: String): String? = lastResponse().headers.getFirst(name)
}
