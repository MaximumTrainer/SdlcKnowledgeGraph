package com.repodatagraph.adapter.out.servicenow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** ServiceNow failed or asked us to come back later. Retried before this escapes. */
class ServiceNowUnavailableException(
    message: String,
) : RuntimeException(message)

/** ServiceNow refused: the integration user may not read that table, or it does not exist. */
class ServiceNowRefusedException(
    message: String,
) : RuntimeException(message)

/**
 * One row of a ServiceNow table, read as it arrives.
 *
 * `sysparm_display_value=all` returns a reference field as `{display_value, value}` and a plain one
 * as a string, and the difference is the whole of CMDB integration: `value` is the sys_id an edge can
 * point at, and `display_value` is a human label that points at nothing. So both are kept, and every
 * read says which of the two it wanted.
 */
class ServiceNowRow(
    private val fields: JsonNode,
) {
    /** The human-readable form: a state, a class name, a group's name. */
    fun display(field: String): String? = read(field, "display_value")

    /** The machine form: a sys_id for a reference, the stored value for anything else. */
    fun value(field: String): String? = read(field, "value")

    val sysId: String? get() = value("sys_id")

    val updatedOn: Instant? get() = ServiceNowTime.parse(value("sys_updated_on"))

    private fun read(
        field: String,
        half: String,
    ): String? {
        val node = fields.path(field)
        val text = if (node.isObject) node.path(half).asText() else node.asText()
        return text.takeIf { it.isNotBlank() && it != "null" }
    }
}

/**
 * ServiceNow's clock, which is UTC without a timezone on it.
 *
 * The Table API both returns and expects `yyyy-MM-dd HH:mm:ss`, so a watermark has to survive a round
 * trip through that format. Parsing it as anything else silently shifts every incremental query by
 * the server's offset from UTC.
 */
object ServiceNowTime {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    fun parse(value: String?): Instant? =
        value
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    java.time.LocalDateTime
                        .parse(it, FORMAT)
                        .toInstant(ZoneOffset.UTC)
                }.getOrNull()
            }

    fun format(instant: Instant): String = FORMAT.format(instant)
}

/**
 * The ServiceNow Table API, as much of it as this connector needs.
 *
 * Everything is one endpoint with the question in the query string, so the query is where the care
 * goes: the window, the ordering and the page. Ordering by `sys_updated_on` is not decoration - it is
 * what makes paging through a table that is being written to at the same time produce each row once.
 */
@Component
class ServiceNowClient(
    private val properties: ServiceNowProperties,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client =
        builder
            .baseUrl(properties.instanceUrl)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json")
            .defaultHeaders { headers -> basicAuth()?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
            .messageConverters { it.add(0, MappingJackson2HttpMessageConverter(JSON)) }
            .build()

    /**
     * Basic auth, or nothing.
     *
     * OAuth client credentials is configurable and not yet implemented: the token exchange is a
     * second endpoint with its own refresh, and shipping a half-built one would be worse than
     * refusing to start. A connector configured for OAuth says so in its health check.
     */
    private fun basicAuth(): String? {
        if (properties.auth.mode != ServiceNowAuthMode.BASIC || properties.auth.username.isBlank()) return null
        val credentials = "${properties.auth.username}:${properties.auth.password}"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    /**
     * Every row of a table updated since [since], page by page.
     *
     * A sequence, because a CMDB's change table is years of history and the caller writes each page
     * as it arrives. Paging stops on a short page rather than on a count: `sysparm_count` is a second
     * request, and on a table being written to it is out of date by the time it is answered.
     */
    fun rows(
        table: String,
        since: Instant?,
    ): Sequence<ServiceNowRow> =
        sequence {
            var offset = 0
            while (true) {
                val page = page(table, since, offset)
                yieldAll(page)
                if (page.size < properties.pageSize) return@sequence
                offset += properties.pageSize
            }
        }

    /** Whether the instance answers at all, for the connector's health check. */
    fun isReachable(): Boolean =
        try {
            page("sys_properties", since = null, offset = 0, limit = 1)
            true
        } catch (unreachable: ServiceNowUnavailableException) {
            log.warn("ServiceNow is not reachable: {}", unreachable.message)
            false
        } catch (refused: ServiceNowRefusedException) {
            log.warn("ServiceNow refused the health check: {}", refused.message)
            false
        }

    private fun page(
        table: String,
        since: Instant?,
        offset: Int,
        limit: Int = properties.pageSize,
    ): List<ServiceNowRow> {
        val response =
            withRetries {
                client
                    .get()
                    .uri { uri ->
                        uri
                            .path("/api/now/table/$table")
                            .queryParam("sysparm_limit", limit)
                            .queryParam("sysparm_offset", offset)
                            // Ordered, so that paging a table being written to returns each row once.
                            .queryParam("sysparm_query", query(since))
                            // Both halves of every reference: the label for a person, the sys_id for
                            // an edge. Without this a reference arrives as a sys_id with no meaning.
                            .queryParam("sysparm_display_value", "all")
                            .queryParam("sysparm_exclude_reference_link", "true")
                            .build()
                    }.exchange { _, response ->
                        failUnlessOk(response.statusCode.value(), table, response.headers)
                        response.bodyTo(JsonNode::class.java)
                    }
            }
        return response?.path("result")?.map { ServiceNowRow(it) }.orEmpty()
    }

    private fun query(since: Instant?): String =
        listOfNotNull(
            since?.let { "sys_updated_on>=${ServiceNowTime.format(it)}" },
            "ORDERBYsys_updated_on",
        ).joinToString("^")

    /**
     * Retries what is worth retrying, waiting as long as ServiceNow asks.
     *
     * An instance under load answers 429 with `Retry-After`, and ignoring it is how an integration
     * user gets its access revoked. The wait is bounded for the same reason the GitHub one is: a run
     * that sleeps for minutes holds a worker thread that every other connector is sharing.
     */
    private fun <T> withRetries(call: () -> T): T {
        var last: ServiceNowUnavailableException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return call()
            } catch (unavailable: ServiceNowUnavailableException) {
                last = unavailable
                log.info("ServiceNow failed ({}), attempt {} of {}", unavailable.message, attempt + 1, MAX_ATTEMPTS)
                Thread.sleep(minOf(retryAfter ?: Duration.ofMillis(BACKOFF_MILLIS * (attempt + 1)), MAX_WAIT).toMillis())
            }
        }
        throw checkNotNull(last)
    }

    /** Set by the failing response, read by the retry that follows it. */
    private var retryAfter: Duration? = null

    private fun failUnlessOk(
        status: Int,
        table: String,
        headers: HttpHeaders,
    ) {
        if (status < BAD_REQUEST) {
            retryAfter = null
            return
        }
        // 429 and 5xx are "not now"; every other refusal would answer the same way however often it
        // is asked, and retrying one only spends the integration user's quota on being refused.
        if (status != TOO_MANY_REQUESTS && status < SERVER_ERROR) {
            throw ServiceNowRefusedException("ServiceNow answered $status for $table")
        }
        retryAfter =
            headers
                .getFirst("Retry-After")
                ?.toLongOrNull()
                ?.let { Duration.ofSeconds(it) }
        throw ServiceNowUnavailableException("ServiceNow answered $status for $table")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MILLIS = 250L
        const val BAD_REQUEST = 400
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
        val MAX_WAIT: Duration = Duration.ofSeconds(30)
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
