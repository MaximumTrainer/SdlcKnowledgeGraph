package com.repodatagraph.adapter.out.ontology

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.domain.ontology.InvalidOntologyException

/** A YAML value as the plain value the API would bind it to, keeping key order (#81). */
internal fun JsonNode.plain(): Any? =
    when {
        isNull -> null
        isTextual -> asText()
        isBoolean -> asBoolean()
        isInt -> asInt()
        isIntegralNumber -> asLong()
        isNumber -> asDouble()
        isArray -> map { it.plain() }
        isObject -> properties().associateTo(LinkedHashMap()) { (key, value) -> key to value.plain() }
        else -> asText()
    }

/**
 * A key the loader does not read is a typo or a feature that does not exist, and either way the
 * registry would say something nothing enforces; so it fails loading rather than being ignored (#81).
 */
internal fun JsonNode.requireOnly(
    allowed: Set<String>,
    owner: String,
) {
    if (!isObject) return
    val unknown = properties().map { it.key }.filter { it !in allowed }
    if (unknown.isNotEmpty()) {
        throw InvalidOntologyException("$owner has unknown key ${unknown.joinToString()}; known keys are ${allowed.joinToString()}")
    }
}
