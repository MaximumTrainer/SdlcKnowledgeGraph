package com.repodatagraph.application

import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Checks submitted properties against the type the registry declares.
 *
 * One validator serves both nodes and edges. The registry declares their properties with the same
 * shape, so a second implementation would only be a second place for the rules to drift.
 *
 * There is one validator rather than one per type because the rules live in the ontology: a type
 * added there is validated by the same code. Everything external is untrusted, so a property
 * the registry does not declare is refused rather than stored and ignored — otherwise a typo becomes
 * a silent data-quality problem, and an attacker-chosen key reaches the store.
 *
 * Every problem is reported at once, so a form can show a user all of them rather than one per
 * round trip.
 */
@Component
class PropertyValidator {
    fun validate(
        nodeType: NodeTypeDef,
        props: Map<String, Any?>,
    ): List<PropertyError> = validate(nodeType.properties, props)

    fun validate(
        declared: List<PropertyDef>,
        props: Map<String, Any?>,
    ): List<PropertyError> {
        val byName = declared.associateBy { it.name }

        val undeclared =
            props.keys
                .filter { it !in byName }
                .map { PropertyError(it, NOT_IN_ONTOLOGY) }

        val missing =
            declared
                .filter { it.required }
                .filter { isAbsent(props, it.name) }
                .map { PropertyError(it.name, "${it.name} is required") }

        val supplied = declared.filter { props.containsKey(it.name) && props[it.name] != null }

        val wrongType =
            supplied
                .filterNot { matches(it.type, props[it.name]) }
                .map { PropertyError(it.name, "expected ${it.type.wireName}") }

        // A value outside a declared set is reported with the set: "invalid" tells a user nothing
        // they can act on, and the allowed values are right there in the registry.
        val outsideEnum =
            supplied
                .filter { matches(it.type, props[it.name]) }
                .mapNotNull { property ->
                    val allowed = property.enum ?: return@mapNotNull null
                    if (props[property.name].toString() in allowed) {
                        null
                    } else {
                        PropertyError(property.name, "${property.name} must be one of ${allowed.joinToString()}")
                    }
                }

        // Reported in declaration order rather than submission order, so two clients sending the
        // same bad request get the same response.
        return (missing + wrongType + outsideEnum)
            .sortedBy { error -> declared.indexOfFirst { it.name == error.field } } + undeclared
    }

    private fun isAbsent(
        props: Map<String, Any?>,
        name: String,
    ): Boolean =
        when (val value = props[name]) {
            null -> true
            is String -> value.isBlank()
            else -> false
        }

    private fun matches(
        type: PropertyType,
        value: Any?,
    ): Boolean =
        when (type) {
            PropertyType.STRING -> value is String
            // Booleans are numbers in some serialisations; accepting one here would let `true`
            // become 1 in the graph, which no later reader could tell from a real count.
            PropertyType.INT -> value is Int || value is Long || value is Short
            PropertyType.BOOLEAN -> value is Boolean
            PropertyType.INSTANT -> value is Instant || (value is String && runCatching { Instant.parse(value) }.isSuccess)
            PropertyType.STRING_ARRAY -> value is Collection<*> && value.all { it is String }
        }

    private companion object {
        const val NOT_IN_ONTOLOGY = "not in ontology"
    }
}
