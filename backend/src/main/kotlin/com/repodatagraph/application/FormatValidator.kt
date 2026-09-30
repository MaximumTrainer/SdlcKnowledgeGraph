package com.repodatagraph.application

import com.repodatagraph.domain.ontology.PropertyFormat
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * Whether a value has the shape its property declares (#81).
 *
 * Each rule accepts every form the graph's writers produce today and refuses what is plainly
 * something else. None is a full parser: a format is there to catch a value put in the wrong field,
 * not to certify one. buildSrc's FormatRules holds the same rules for the ontology lint, which cannot
 * import this class; their tests accept and refuse the same values.
 */
object FormatValidator {
    private val SEMVER =
        Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$""",
        )

    // Any scheme followed by something, which covers https, ssh, git and a work item's chorus://, or
    // the scp-like git@host:path form a git remote is often written in.
    private val URL = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://\S+$""")
    private val SCP_LIKE = Regex("""^[\w.-]+@[\w.-]+:\S+$""")
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    private val SHA256 = Regex("""^sha256:[0-9a-f]{64}$""")
    private val ARN = Regex("""^arn:[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]*:[0-9]*:.+$""")
    private val RRULE = Regex("""^(RRULE:)?(.+;)?FREQ=(SECONDLY|MINUTELY|HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY)(;.*)?$""")

    /** Each format's rule, over a value already known to be a string. */
    private val RULES: Map<PropertyFormat, (String) -> Boolean> =
        mapOf(
            PropertyFormat.INSTANT to ::isInstant,
            PropertyFormat.URL to { text -> URL.matches(text) || SCP_LIKE.matches(text) },
            PropertyFormat.EMAIL to EMAIL::matches,
            // An IANA name, which has a slash, or UTC itself; not a bare offset, and not "london".
            PropertyFormat.IANA_TZ to { text -> text == "UTC" || (text.contains('/') && isZone(text)) },
            PropertyFormat.RRULE to RRULE::matches,
            PropertyFormat.SEMVER to SEMVER::matches,
            PropertyFormat.SHA256 to SHA256::matches,
            PropertyFormat.ARN to ARN::matches,
        )

    /** True for a value of the format's shape, and for no value at all: absence is `required`'s concern. */
    fun matches(
        format: PropertyFormat,
        value: Any?,
    ): Boolean =
        when (value) {
            null -> true
            is Instant -> format == PropertyFormat.INSTANT
            is String -> RULES.getValue(format)(value)
            else -> false
        }

    private fun isInstant(text: String): Boolean =
        try {
            Instant.parse(text)
            true
        } catch (_: DateTimeException) {
            false
        }

    private fun isZone(text: String): Boolean =
        try {
            ZoneId.of(text)
            true
        } catch (_: DateTimeException) {
            false
        }
}
