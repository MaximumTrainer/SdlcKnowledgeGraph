package com.repodatagraph.domain.ontology

/** The value types a property may take. The wire name is what `GET /api/v1/ontology` publishes. */
enum class PropertyType(
    val wireName: String,
) {
    STRING("string"),
    INT("int"),
    BOOLEAN("boolean"),
    INSTANT("instant"),
    STRING_ARRAY("string[]"),
    ;

    companion object {
        fun fromWireName(value: String): PropertyType =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidOntologyException("unknown property type '$value'")
    }
}

data class PropertyDef(
    val name: String,
    val type: PropertyType,
    val required: Boolean = false,
    val description: String? = null,
    /**
     * The values this property may take, when the ontology constrains them.
     *
     * A free-text `kind` on a dependency is barely worth storing: nobody can query for "all the
     * event-driven dependencies" if half of them say "events" and the rest say "async". Declaring
     * the set is what makes the property answerable.
     */
    val enum: List<String>? = null,
)

data class NodeTypeDef(
    val name: String,
    val description: String?,
    /** Property names whose values, in order, form the node's key. */
    val identity: List<String>,
    val properties: List<PropertyDef>,
) {
    fun property(name: String): PropertyDef? = properties.firstOrNull { it.name == name }

    fun requiredProperties(): List<PropertyDef> = properties.filter { it.required }
}

data class EdgeTypeDef(
    val name: String,
    val description: String?,
    val from: List<String>,
    val to: List<String>,
    /** Name used when traversing this edge backwards. Not stored as a second edge. */
    val inverse: String,
    val properties: List<PropertyDef> = emptyList(),
) {
    fun connects(
        fromType: String,
        toType: String,
    ): Boolean = fromType in from && toType in to
}

class InvalidOntologyException(
    message: String,
) : RuntimeException(message)

class OntologyDriftException(
    message: String,
) : RuntimeException(message)

class IdentityResolutionException(
    message: String,
) : RuntimeException(message)
