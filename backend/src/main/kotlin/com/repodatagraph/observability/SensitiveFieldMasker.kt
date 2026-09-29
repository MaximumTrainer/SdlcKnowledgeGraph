package com.repodatagraph.observability

/**
 * Masks the value of any field whose name looks like a secret, at any depth, before it is logged
 * (#44, FR6).
 *
 * By name rather than by value, because a secret's value has no shape to recognise and its name
 * usually does. The whole value is replaced, whatever it is, so a map of credentials is not walked
 * for the parts that happen to look harmless.
 */
object SensitiveFieldMasker {
    const val MASK = "***"

    private val SECRET_NAME = Regex("password|token|secret|authorization|api[-_]?key|cookie|credential", RegexOption.IGNORE_CASE)

    fun mask(fields: Map<String, Any?>): Map<String, Any?> =
        fields.mapValues { (name, value) -> if (SECRET_NAME.containsMatchIn(name)) MASK else maskValue(value) }

    private fun maskValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> mask(value.entries.associate { (key, inner) -> key.toString() to inner })
            is Collection<*> -> value.map(::maskValue)
            else -> value
        }
}
