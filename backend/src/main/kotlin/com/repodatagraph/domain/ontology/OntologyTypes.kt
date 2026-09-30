package com.repodatagraph.domain.ontology

/** The value types a property may take. The wire name is what `GET /api/v1/ontology` publishes. */
enum class PropertyType(
    val wireName: String,
) {
    STRING("string"),
    INT("int"),
    FLOAT("float"),
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

/**
 * A system of record a fact's provenance may name (#117): `manual`, for what a principal states
 * through the API, or the system a connector reads. Declared in sources.yaml; writing as any source
 * but `manual` needs the `graph:write:<name>` scope.
 */
data class SourceSystemDef(
    val name: String,
    val description: String? = null,
)

data class NodeTypeDef(
    val name: String,
    val description: String?,
    /** Property names whose values, in order, form the node's key. */
    val identity: List<String>,
    val properties: List<PropertyDef>,
    /** Describes the graph itself (its ontology, its sync runs) rather than the software it models. */
    val meta: Boolean = false,
    /**
     * The property a node of this type is labelled with where it is drawn (#9), such as a
     * repository's `name` rather than its whole key. Null labels it with its key.
     */
    val displayProperty: String? = null,
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
    /** Whether a change to one end reaches the other (#21): what an impact traversal walks. */
    val impact: EdgeImpact = EdgeImpact.NONE,
    /** Which way a change travels along a propagating edge: as stored, or along its inverse. */
    val downstream: ImpactAlong = ImpactAlong.FORWARD,
    /** Whether this edge names an owner, or passes its source's owner on to its target (#21). */
    val ownership: EdgeOwnership = EdgeOwnership.NONE,
) {
    fun connects(
        fromType: String,
        toType: String,
    ): Boolean = fromType in from && toType in to
}

/** Whether a change travels along an edge (#21). The wire name is what edges.yaml and the ontology endpoint say. */
enum class EdgeImpact(
    val wireName: String,
) {
    NONE("none"),
    PROPAGATES("propagates"),
    ;

    companion object {
        fun fromWireName(value: String): EdgeImpact = fromWire(entries, value, "impact") { it.wireName }
    }
}

/**
 * Which way "downstream" runs along a propagating edge. `DEPENDS_ON` points from the dependent to what
 * it depends on, but a change travels the other way, so its downstream is [INVERSE]: `DEPENDED_ON_BY`.
 */
enum class ImpactAlong(
    val wireName: String,
) {
    FORWARD("forward"),
    INVERSE("inverse"),
    ;

    companion object {
        fun fromWireName(value: String): ImpactAlong = fromWire(entries, value, "downstream") { it.wireName }
    }
}

/**
 * What an edge says about ownership (#21). [OWNER] names the owner at its target; [INHERITS] passes the
 * owner of whatever a change comes from on to what it reaches, so a cloud resource with no owner of
 * its own is owned by whoever owns the repository that owns it.
 */
enum class EdgeOwnership(
    val wireName: String,
) {
    NONE("none"),
    OWNER("owner"),
    INHERITS("inherits"),
    ;

    companion object {
        fun fromWireName(value: String): EdgeOwnership = fromWire(entries, value, "ownership") { it.wireName }
    }
}

private fun <T> fromWire(
    entries: List<T>,
    value: String,
    flag: String,
    wireName: (T) -> String,
): T =
    entries.firstOrNull { wireName(it) == value }
        ?: throw InvalidOntologyException("unknown $flag '$value', expected one of ${entries.map(wireName)}")

class InvalidOntologyException(
    message: String,
) : RuntimeException(message)

class OntologyDriftException(
    message: String,
) : RuntimeException(message)

class IdentityResolutionException(
    message: String,
) : RuntimeException(message)
