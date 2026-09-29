package com.repodatagraph.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * The deploy pipeline's access to `POST /api/v1/ingest/deployment` (#7), bound from `ingest.*`.
 *
 * @param token the bearer token the pipeline sends. Empty by default, which turns the endpoint off
 *   (503) rather than leaving it open: an instance nobody gave a token to has nobody entitled to write
 *   through it.
 */
@ConfigurationProperties("ingest")
data class IngestProperties(
    @DefaultValue("") val token: String = "",
) {
    val enabled: Boolean get() = token.isNotBlank()
}
