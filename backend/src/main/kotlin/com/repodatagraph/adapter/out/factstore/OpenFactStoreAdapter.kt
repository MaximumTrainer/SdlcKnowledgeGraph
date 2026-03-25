package com.repodatagraph.adapter.out.factstore

import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.port.out.FactStorePort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Instant
import java.util.UUID

data class FactStoreTrailRequest(
    val flowId: String,
    val gitCommitSha: String?,
    val gitBranch: String?,
    val gitAuthor: String?,
    val orgSlug: String,
    val buildUrl: String?
)

data class FactStoreTrailResponse(val id: String)

@Component
class OpenFactStoreAdapter(
    @Value("\${factstore.base-url:http://localhost:8090}") private val baseUrl: String,
    @Value("\${factstore.org-slug:repodatagraph}") private val orgSlug: String,
    @Value("\${factstore.api-key:}") private val apiKey: String
) : FactStorePort {

    private val restClient: RestClient by lazy {
        RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Api-Key", apiKey)
            .build()
    }

    override fun recordEvent(event: AuditEvent) {
        try {
            val request = FactStoreTrailRequest(
                flowId = event.eventType,
                gitCommitSha = event.details["commitSha"],
                gitBranch = event.details["branch"],
                gitAuthor = event.actor,
                orgSlug = orgSlug,
                buildUrl = event.details["buildUrl"]
            )
            restClient.post()
                .uri("/api/v1/trails")
                .body(request)
                .retrieve()
                .toEntity(FactStoreTrailResponse::class.java)
        } catch (e: RestClientException) {
            // Log and continue - factstore is non-critical for write path
        }
    }

    override fun queryEvents(repoId: String): List<AuditEvent> {
        return try {
            val response = restClient.get()
                .uri("/api/v1/audit?actor=repo:$repoId")
                .retrieve()
                .toEntity(AuditEventListResponse::class.java)
                .body
            response?.events?.map { it.toDomain() } ?: emptyList()
        } catch (e: RestClientException) {
            emptyList()
        }
    }
}

data class AuditEventListResponse(val events: List<AuditEventDto> = emptyList())

data class AuditEventDto(
    val id: String = UUID.randomUUID().toString(),
    val eventType: String = "",
    val actor: String? = null,
    val timestamp: String? = null
) {
    fun toDomain() = AuditEvent(
        id = id,
        eventType = eventType,
        actor = actor,
        timestamp = timestamp?.let { Instant.parse(it) } ?: Instant.now()
    )
}
