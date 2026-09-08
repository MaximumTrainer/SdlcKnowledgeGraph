package com.repodatagraph.application

import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.PropertyType
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Checks submitted properties against the type the registry declares.
 *
 * There is one validator rather than one per node type because the rules live in the ontology: a
 * type added there is validated by the same code. Everything external is untrusted, so a property
 * the registry does not declare is refused rather than stored and ignored — otherwise a typo becomes
 * a silent data-quality problem, and an attacker-chosen key reaches the store.
 *
 * Every problem is reported at once, so a form can show a user all of them rather than one per
 * round trip.
 */
@Component
class NodeValidator {
    fun validate(
        nodeType: NodeTypeDef,
        props: Map<String, Any?>,
    ): List<PropertyError> {
        val undeclared =
            props.keys
                .filter { nodeType.property(it) == null }
                .map { PropertyError(it, NOT_IN_ONTOLOGY) }

        val missing =
            nodeType
                .requiredProperties()
                .filter { isAbsent(props, it.name) }
                .map { PropertyError(it.name, "${it.name} is required") }

        val wrongType =
            nodeType.properties
                .filter { props.containsKey(it.name) && props[it.name] != null }
                .filterNot { matches(it.type, props[it.name]) }
                .map { PropertyError(it.name, "expected ${it.type.wireName}") }

        // Reported in declaration order rather than submission order, so two clients sending the
        // same bad request get the same response.
        return (missing + wrongType).sortedBy { error -> nodeType.properties.indexOfFirst { it.name == error.field } } +
            undeclared
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
