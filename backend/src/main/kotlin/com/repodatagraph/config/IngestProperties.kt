package com.repodatagraph.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Access to the ingest endpoints, bound from `ingest.*`: `POST /api/v1/ingest/deployment` for the
 * deploy pipeline (#7) and `POST /api/v1/ingest/seed` for the dogfood seed (#47).
 *
 * @param token the bearer token both send. Empty by default, which turns the endpoint off
 *   (503) rather than leaving it open: an instance nobody gave a token to has nobody entitled to write
 *   through it.
 */
@ConfigurationProperties("ingest")
data class IngestProperties(
    @DefaultValue("") val token: String = "",
) {
    val enabled: Boolean get() = token.isNotBlank()
}
