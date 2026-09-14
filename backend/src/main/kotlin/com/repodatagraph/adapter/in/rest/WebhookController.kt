package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.SyncAccepted
import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.application.connector.UnknownConnectorException
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Where source systems push what they know, rather than waiting to be asked.
 *
 * The body arrives as raw bytes on purpose. A signature is computed over exactly what was sent, so
 * parsing it to an object and re-serialising - which is what binding to a type would do - would
 * change the bytes and make every signature fail, or worse, appear to succeed against a different
 * payload from the one that was signed.
 *
 * Verification happens before the connector is told anything at all. An unverified payload never
 * reaches `onWebhook`, so a connector cannot act on a forged event even by accident.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Push ingestion from systems of record")
class WebhookController(
    private val registry: AdapterRegistry,
    private val syncService: SyncService,
) {
    /**
     * Three exits for three different refusals: this connector does not take webhooks, the signature
     * did not check out, or it did. A caller acts differently on each, so they stay distinct.
     */
    @Suppress("ReturnCount")
    @PostMapping("/{name}")
    @Operation(summary = "Accept a signed webhook from a source system")
    fun receive(
        @PathVariable name: String,
        @RequestBody(required = false) body: ByteArray?,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)
        if (!registered.descriptor.supports(Capability.WEBHOOK)) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                mapOf("error" to "connector does not accept webhooks", "connector" to name),
            )
        }

        val payload = body ?: ByteArray(0)
        val headers = headersOf(request)
        if (!registered.connector.verifyWebhook(headers, payload)) {
            // Deliberately says nothing about why. A response that distinguished "no signature" from
            // "wrong signature" would help someone guessing at one.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                mapOf("error" to "signature could not be verified"),
            )
        }

        val runId = syncService.applyWebhook(name, WebhookEvent(name, headers, payload))
        // 204 when the connector decided the event means nothing: accepted, but nothing to follow.
        return runId?.let { ResponseEntity.accepted().body(SyncAccepted(it)) }
            ?: ResponseEntity.noContent().build()
    }

    private fun headersOf(request: HttpServletRequest): Map<String, String> =
        request
            .headerNames
            .toList()
            .associateWith { request.getHeader(it) }
}
