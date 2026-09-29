package com.repodatagraph.application.ingest

import java.security.MessageDigest

/**
 * What the ingest endpoint and the github-actions connector have to agree on, in one place: the
 * connector's name, how a report is authenticated, and how a delivery is named.
 */
object DeploymentReports {
    /** The connector a report is applied through, and the source system its facts are credited to. */
    const val CONNECTOR = "github-actions"

    private const val BEARER = "Bearer "

    /** Whether [authorization] is `Bearer <token>`, compared in constant time. Never true for an empty token. */
    fun bearerMatches(
        authorization: String?,
        token: String,
    ): Boolean {
        if (token.isBlank() || authorization == null || !authorization.startsWith(BEARER)) return false
        return MessageDigest.isEqual(authorization.removePrefix(BEARER).toByteArray(), token.toByteArray())
    }

    /** The same bytes are the same report; a hash is short enough to store and look up. */
    fun deliveryIdOf(body: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
}
