package com.repodatagraph.domain.ontology

import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * Checks the typed Kotlin classes against the registry and refuses to start on a mismatch.
 *
 * The nine core types are defined twice on purpose: once in the registry, which everything reads,
 * and once as Kotlin classes, so the hand-written traversal code keeps compile-time safety. Two
 * definitions of the same thing drift, and drift in a schema is discovered as an empty query result
 * weeks later. Failing at startup makes it a five-second problem instead.
 *
 * The rule is directional. Every required registry property must exist on the class, and every class
 * property must be declared in the registry. An optional registry property may be absent from the
 * class, because connectors populate properties the core model does not model itself.
 */
class OntologyDriftValidator(
    private val registry: OntologyRegistry,
) {
    fun validate(classesByTypeName: Map<String, KClass<*>>) {
        val problems =
            classesByTypeName.flatMap { (typeName, kClass) ->
                val nodeType = registry.nodeType(typeName)
                if (nodeType == null) {
                    listOf("$typeName has a Kotlin class but no entry in the ontology registry")
                } else {
                    problemsFor(typeName, nodeType, kClass)
                }
            }

        if (problems.isNotEmpty()) {
            throw OntologyDriftException(
                "the Kotlin model and the ontology registry disagree:" +
                    problems.joinToString(separator = "") { "\n  - $it" },
            )
        }
    }

    private fun problemsFor(
        typeName: String,
        nodeType: NodeTypeDef,
        kClass: KClass<*>,
    ): List<String> {
        val classProperties = kClass.memberProperties.map { it.name }.toSet() - STRUCTURAL_PROPERTIES
        val declaredProperties = nodeType.properties.map { it.name }.toSet()

        val missingFromClass =
            nodeType
                .requiredProperties()
                .map { it.name }
                .filterNot { it in classProperties }
                .map { "$typeName.$it is required by the registry but the Kotlin class does not declare it" }

        val missingFromRegistry =
            classProperties
                .filterNot { it in declaredProperties }
                .sorted()
                .map { "$typeName.$it exists on the Kotlin class but is not declared in the registry" }

        return missingFromClass + missingFromRegistry
    }

    private companion object {
        /** `id` is how a node is addressed, not something the ontology describes. */
        val STRUCTURAL_PROPERTIES = setOf("id")
    }
}
