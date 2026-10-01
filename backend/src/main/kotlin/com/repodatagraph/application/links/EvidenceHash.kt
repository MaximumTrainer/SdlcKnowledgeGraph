package com.repodatagraph.application.links

import java.security.MessageDigest

/**
 * Hashes and encodings of a link's evidence (#28, FR5).
 *
 * Evidence is stored on an edge as sorted `key=value` entries, since the registry has no map type.
 * Its hash, over the rule and those entries, is what a rejection is pinned to: the same evidence
 * hashes the same however it was assembled, so a rejected candidate stays rejected until the
 * evidence behind it changes. Each part is length-prefixed, so no two different sets of entries can
 * be run together into the same text.
 */
object EvidenceHash {
    private const val HEX_LENGTH = 16

    fun of(
        rule: String,
        evidence: Map<String, String>,
    ): String {
        val text =
            buildString {
                part(rule)
                evidence.toSortedMap().forEach { (key, value) ->
                    part(key)
                    part(value)
                }
            }
        return sha256(text)
    }

    /** A candidate's id: stable for its resource and repository, whatever the evidence. */
    fun candidateId(
        resourceKey: String,
        repoKey: String,
    ): String = sha256(buildString { part(resourceKey).part(repoKey) })

    fun encode(evidence: Map<String, String>): List<String> = evidence.toSortedMap().map { (key, value) -> "$key=$value" }

    fun decode(entries: List<String>): Map<String, String> =
        entries.associate { entry ->
            val at = entry.indexOf('=')
            if (at < 0) entry to "" else entry.substring(0, at) to entry.substring(at + 1)
        }

    /** [decode] for what a store hands back, which may be any list or nothing. */
    fun decodeStored(stored: Any?): Map<String, String> = decode((stored as? Collection<*>).orEmpty().map { it.toString() })

    private fun StringBuilder.part(text: String): StringBuilder = append(text.length).append(':').append(text).append(';')

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HEX_LENGTH)
}
