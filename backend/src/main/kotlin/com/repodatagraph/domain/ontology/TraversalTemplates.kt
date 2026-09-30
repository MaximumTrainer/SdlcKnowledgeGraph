package com.repodatagraph.domain.ontology

/**
 * A named shape of task a context pack is built for (#96, FR-1), declared in templates.yaml: which
 * node types it starts from, the edges it walks from there, and whether each node reached brings
 * its owners with it. The walk is bounded by the template, never by the caller: a caller names a
 * template and a budget, and cannot ask for an unbounded traversal.
 */
data class TemplateDef(
    val name: String,
    val description: String?,
    /** The node types a pack of this template may start from. */
    val start: List<String>,
    /** Whether every node reached, the start among them, brings the teams its owner edges name. */
    val owners: Boolean = false,
    val steps: List<TemplateStepDef>,
)

/**
 * One edge of a template, walked from every node the step before it reached (the start, for a
 * first step) and then on to [then] from every node it reaches.
 *
 * [edge] is an edge type's own name, walked as stored, or its inverse, walked against it: so
 * `DEPENDED_ON_BY` walks DEPENDS_ON back to what depends on a node. [where] keeps only the edges
 * whose properties hold those values. The step repeats [min] to [max] times; a [min] of 0 lets
 * [then] start from where the step started too, so `DEPENDED_ON_BY*` then `BUILDS` builds from the
 * start and from each of its dependants. [current] keeps, of the deployments the step reaches, only
 * those still running (see `CurrentDeployments`).
 */
data class TemplateStepDef(
    val edge: String,
    val where: Map<String, String> = emptyMap(),
    val min: Int = 1,
    val max: Int = 1,
    val current: Boolean = false,
    val then: List<TemplateStepDef> = emptyList(),
) {
    companion object {
        /** The most times one step repeats: far enough for a dependency chain, near enough to stay bounded. */
        const val MAX_REPEAT = 5

        /** The one node type [current] can narrow: what is running is a fact about deployments. */
        const val CURRENT_TYPE = "Deployment"
    }
}

/** An edge as a template walks it: along its stored direction, or against it by its inverse name. */
data class WalkableEdge(
    val type: EdgeTypeDef,
    val alongStoredDirection: Boolean,
) {
    /** The node types a walk along this edge leaves from. */
    val sources: List<String> get() = if (alongStoredDirection) type.from else type.to

    /** The node types a walk along this edge arrives at. */
    val targets: List<String> get() = if (alongStoredDirection) type.to else type.from
}

/** What can name a template: lower-case words joined by hyphens, as a URL segment or tool argument would. */
private val TEMPLATE_NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

/**
 * Everything wrong with [template], each as a phrase to follow its name, checked against the node
 * types and edges the registry declares. The walk is followed type by type from the start, so an
 * edge that exists but cannot be walked from where the template stands is as wrong as one that does
 * not exist: either would answer an empty pack rather than fail.
 */
internal fun templateProblems(
    template: TemplateDef,
    isNodeType: (String) -> Boolean,
    walkable: (String) -> WalkableEdge?,
): List<String> {
    val problems = mutableListOf<String>()
    if (!TEMPLATE_NAME.matches(template.name)) problems += "is not named in lower-case words joined by hyphens"
    if (template.description.isNullOrBlank()) problems += "has no description"
    if (template.start.isEmpty()) problems += "declares no start type"
    template.start.filterNot(isNodeType).forEach { problems += "starts from undeclared node type '$it'" }
    if (template.steps.isEmpty()) problems += "declares no steps"
    template.steps.forEach { problems += stepProblems(it, template.start.filter(isNodeType).toSet(), walkable) }
    return problems
}

private fun stepProblems(
    step: TemplateStepDef,
    from: Set<String>,
    walkable: (String) -> WalkableEdge?,
): List<String> {
    val edge =
        walkable(step.edge) ?: return listOf("walks '${step.edge}', which is neither an edge type nor an edge's inverse")
    val problems = mutableListOf<String>()
    if (from.isNotEmpty() && from.none { it in edge.sources }) {
        problems += "cannot walk '${step.edge}' from ${from.sorted().joinToString(" or ")}"
    }
    problems += whereProblems(step, edge)
    if (!repeatsWithinBounds(step)) {
        problems += "repeats '${step.edge}' ${step.min} to ${step.max} times; a repeat is 0 to ${TemplateStepDef.MAX_REPEAT} times, " +
            "at least once at most"
    }
    if (step.current && edge.targets != listOf(TemplateStepDef.CURRENT_TYPE)) {
        problems +=
            "marks '${step.edge}' current, which reaches ${edge.targets.joinToString()} rather than only ${TemplateStepDef.CURRENT_TYPE}"
    }
    // What the steps after this one stand on: what it reaches, and where it started when it may repeat no times.
    val reached = edge.targets.toSet() + if (step.min == 0) from else emptySet()
    step.then.forEach { problems += stepProblems(it, reached, walkable) }
    return problems
}

/** A property filter the edge does not declare, or a value its enum refuses. */
private fun whereProblems(
    step: TemplateStepDef,
    edge: WalkableEdge,
): List<String> =
    step.where.mapNotNull { (name, value) ->
        val property = edge.type.properties.firstOrNull { it.name == name }
        when {
            property == null -> "filters '${step.edge}' on '$name', which ${edge.type.name} does not declare"
            property.enum != null && value !in property.enum ->
                "filters '${step.edge}' on $name '$value', which is not one of ${property.enum.joinToString()}"
            else -> null
        }
    }

/** Whether a step repeats 0 to [TemplateStepDef.MAX_REPEAT] times, and at least once at most. */
private fun repeatsWithinBounds(step: TemplateStepDef): Boolean = step.min in 0..step.max && step.max in 1..TemplateStepDef.MAX_REPEAT
