import com.fasterxml.jackson.databind.ObjectMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * The shapes a string property may declare with `format:` (#81), as the build reads them.
 *
 * The application enforces the same rules on write (FormatValidator); buildSrc cannot import it, so
 * the two are kept together by ExampleValidatorTest here and FormatValidatorTest there, which accept
 * and refuse the same values.
 */
object FormatRules {
    private val SEMVER =
        Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$""",
        )
    private val URL = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://\S+$""")
    private val SCP_LIKE = Regex("""^[\w.-]+@[\w.-]+:\S+$""")
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    private val SHA256 = Regex("""^sha256:[0-9a-f]{64}$""")
    private val ARN = Regex("""^arn:[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]*:[0-9]*:.+$""")
    private val RRULE = Regex("""^(RRULE:)?(.+;)?FREQ=(SECONDLY|MINUTELY|HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY)(;.*)?$""")

    val KNOWN = linkedSetOf("instant", "url", "email", "iana-tz", "rrule", "semver", "sha256", "arn")

    fun matches(
        format: String,
        value: String,
    ): Boolean =
        when (format) {
            "instant" -> isInstant(value)
            "url" -> URL.matches(value) || SCP_LIKE.matches(value)
            "email" -> EMAIL.matches(value)
            "iana-tz" -> value.contains('/') && isZone(value) || value == "UTC"
            "rrule" -> RRULE.matches(value)
            "semver" -> SEMVER.matches(value)
            "sha256" -> SHA256.matches(value)
            "arn" -> ARN.matches(value)
            else -> false
        }

    fun isInstant(value: String): Boolean =
        try {
            Instant.parse(value)
            true
        } catch (_: DateTimeException) {
            false
        }

    private fun isZone(value: String): Boolean =
        try {
            ZoneId.of(value)
            true
        } catch (_: DateTimeException) {
            false
        }
}

/** Whether a value is one its property would accept: of its type, in its enum, of its format. */
object ExampleValidator {
    fun problems(
        property: GenProperty,
        value: Any?,
        siblings: Map<String, Any?> = emptyMap(),
    ): List<String> {
        if (!ofType(property.type, value)) return listOf("is not of type ${property.type}")
        val enum = property.enum
        if (enum != null && value.toString() !in enum) return listOf("is not one of ${enum.joinToString()}")
        val format = property.format ?: return emptyList()
        val applies = property.formatWhen.isEmpty() || property.formatWhen.all { (key, expected) -> siblings[key]?.toString() == expected }
        return if (applies && value is String && format in FormatRules.KNOWN && !FormatRules.matches(format, value)) {
            listOf("is not of format $format")
        } else {
            emptyList()
        }
    }

    private fun ofType(
        type: String,
        value: Any?,
    ): Boolean =
        when (type) {
            "int" -> value is Int || value is Long
            "float" -> value is Number
            "boolean" -> value is Boolean
            "instant" -> value is String && FormatRules.isInstant(value)
            "string[]" -> value is List<*> && value.all { it is String }
            else -> value is String
        }
}

/** One thing the lint found, locatable by its registry path. */
data class LintFinding(
    val path: String,
    val message: String,
    val code: String,
) {
    val line: String get() = "$path: $message [$code]"

    /** The type the finding is about, `nodes.Repository` or `edges.OWNED_BY`, for grouping. */
    val group: String get() = path.split('.').take(2).joinToString(".")
}

/**
 * Whether the registry describes itself well enough for a machine to read it (#81).
 *
 * Every rule has a code, documented in docs/ONTOLOGY.md, so a failure can be looked up rather than
 * guessed at. ONT009 (a description too thin to help) is the only rule that `--warn-only` relaxes:
 * the rest are facts about the registry, not matters of taste.
 */
object OntologyLint {
    /** The types an agent reaches for first; each must say which questions it answers. */
    val CORE_TYPES =
        setOf("Repository", "Team", "Service", "Pipeline", "Artifact", "Deployment", "Environment", "CloudResource", "ConfigurationItem")

    private const val WARNING = "ONT009"
    private const val TEMPLATE = "ONT014"
    private const val SENSITIVITY = "ONT015"

    /** As the runtime's TemplateStepDef: the most times a template step repeats, and what `current` narrows. */
    private const val MAX_REPEAT = 5
    private const val CURRENT_TYPE = "Deployment"
    private const val MIN_DESCRIPTION = 20
    private val JSON = ObjectMapper()

    private val SNAKE = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
    private val KEBAB = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
    private val UPPER_SNAKE = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")

    fun lint(ontology: GenOntology): List<LintFinding> {
        val nodeNames = ontology.nodeTypes.map { it.name }.toSet()
        val edgeNames = ontology.edgeTypes.map { it.name }.toSet()
        val nodes =
            ontology.nodeTypes.flatMap { type ->
                val path = "nodes.${type.name}"
                typeFindings(type, path) +
                    type.properties.flatMap { propertyFindings(it, "$path.properties.${it.name}", type.name, type.properties, edgeNames) } +
                    type.properties
                        .filter { it.name in type.identity && it.deprecated != null }
                        .map { LintFinding("$path.properties.${it.name}", "identity property is deprecated", "ONT011") } +
                    type.examples.flatMapIndexed { index, example -> exampleFindings(example, "$path.examples[$index]", type.properties) } +
                    mergeScopeFindings(type, path)
            }
        val edges =
            ontology.edgeTypes.flatMap { type ->
                val path = "edges.${type.name}"
                edgeFindings(type, path, nodeNames) +
                    type.properties.flatMap { propertyFindings(it, "$path.properties.${it.name}", type.name, type.properties, edgeNames) } +
                    type.examples.flatMapIndexed { index, example -> exampleFindings(example, "$path.examples[$index]", type.properties) }
            }
        return nodes + edges + environmentFindings(ontology.environments) + ontology.templates.flatMap { templateFindings(it, ontology) } +
            sensitivityFindings(ontology)
    }

    /**
     * ONT015: a sensitivity label (#30) is one of [Sensitivity.LEVELS]; a property's is above its
     * type's, since a property can only be hidden from someone who may see the node it is on; and an
     * edge's properties carry none, because an edge is as sensitive as the types it joins.
     */
    private fun sensitivityFindings(ontology: GenOntology): List<LintFinding> {
        val unknown = { level: String -> level !in Sensitivity.LEVELS }
        val types =
            ontology.nodeTypes.flatMap { type ->
                val path = "nodes.${type.name}"
                val own =
                    listOfNotNull(
                        type.sensitivity?.takeIf(unknown)?.let { LintFinding(path, "unknown sensitivity '$it'", SENSITIVITY) },
                    )
                own +
                    type.properties.mapNotNull { property ->
                        val level = property.sensitivity ?: return@mapNotNull null
                        val at = "$path.properties.${property.name}"
                        when {
                            unknown(level) -> LintFinding(at, "unknown sensitivity '$level'", SENSITIVITY)
                            Sensitivity.LEVELS.indexOf(level) <= Sensitivity.LEVELS.indexOf(type.effectiveSensitivity) ->
                                LintFinding(at, "sensitivity '$level' is not above its type's '${type.effectiveSensitivity}'", SENSITIVITY)
                            else -> null
                        }
                    }
            }
        val edges =
            ontology.edgeTypes.flatMap { type ->
                type.properties.filter { it.sensitivity != null }.map {
                    LintFinding("edges.${type.name}.properties.${it.name}", "an edge property carries no sensitivity of its own", SENSITIVITY)
                }
            }
        return types + edges
    }

    /**
     * ONT014: a context pack's template (#96) walks only edges that exist, from the types it stands on,
     * filters only on what an edge declares, repeats within bounds and asks `current` only of a step
     * reaching deployments alone. The application refuses the same templates at startup; the lint says
     * so at build time, every problem at once.
     */
    private fun templateFindings(
        template: GenTemplate,
        ontology: GenOntology,
    ): List<LintFinding> {
        val path = "templates.${template.name}"
        val nodeNames = ontology.nodeTypes.map { it.name }.toSet()
        val own =
            listOfNotNull(
                LintFinding(path, "name is not lower-case words joined by hyphens", TEMPLATE).takeUnless { KEBAB.matches(template.name) },
                LintFinding(path, "missing description", TEMPLATE).takeIf { template.description.isNullOrBlank() },
                LintFinding(path, "declares no start type", TEMPLATE).takeIf { template.start.isEmpty() },
            ) +
                template.start
                    .filterNot { it in nodeNames }
                    .map { LintFinding(path, "starts from undeclared node type '$it'", TEMPLATE) } +
                listOfNotNull(LintFinding(path, "declares no steps", TEMPLATE).takeIf { template.steps.isEmpty() })
        val from = template.start.filter { it in nodeNames }.toSet()
        return own + template.steps.flatMapIndexed { index, step -> stepFindings(step, "$path.steps[$index]", from, ontology) }
    }

    private fun stepFindings(
        step: GenTemplateStep,
        path: String,
        from: Set<String>,
        ontology: GenOntology,
    ): List<LintFinding> {
        val forward = ontology.edgeTypes.firstOrNull { it.name == step.edge }
        val edge =
            forward ?: ontology.edgeTypes.firstOrNull { it.inverse == step.edge }
                ?: return listOf(LintFinding(path, "walks '${step.edge}', which is neither an edge type nor an edge's inverse", TEMPLATE))
        val (sources, targets) = if (forward != null) edge.from to edge.to else edge.to to edge.from
        val findings = mutableListOf<LintFinding>()
        if (from.isNotEmpty() && from.none { it in sources }) {
            findings += LintFinding(path, "cannot walk '${step.edge}' from ${from.sorted().joinToString(" or ")}", TEMPLATE)
        }
        step.where.forEach { (name, value) ->
            val property = edge.properties.firstOrNull { it.name == name }
            when {
                property == null -> findings += LintFinding(path, "filters on '$name', which ${edge.name} does not declare", TEMPLATE)
                property.enum != null && value !in property.enum ->
                    findings += LintFinding(path, "filters $name on '$value', which is not one of ${property.enum.joinToString()}", TEMPLATE)
            }
        }
        if (step.min < 0 || step.max < 1 || step.max > MAX_REPEAT || step.min > step.max) {
            findings +=
                LintFinding(path, "repeats ${step.min} to ${step.max} times; a repeat is 0 to $MAX_REPEAT times, at least once at most", TEMPLATE)
        }
        if (step.current && targets != listOf(CURRENT_TYPE)) {
            findings += LintFinding(path, "marks current a step that reaches ${targets.joinToString()}, not $CURRENT_TYPE alone", TEMPLATE)
        }
        val reached = targets.toSet() + if (step.min == 0) from else emptySet()
        return findings + step.then.flatMapIndexed { index, next -> stepFindings(next, "$path.then[$index]", reached, ontology) }
    }

    /** ONT013: a merge scope (#98) names only properties the type declares. */
    private fun mergeScopeFindings(
        type: GenNodeType,
        path: String,
    ): List<LintFinding> =
        type.mergeScope
            .filter { name -> type.properties.none { it.name == name } }
            .map { LintFinding(path, "mergeScope names '$it', which the type does not declare", "ONT013") }

    /**
     * ONT012: the environment alias table (#98, FR-3) folds each spelling into one environment, never
     * into another environment's own name, and says what each environment is.
     */
    private fun environmentFindings(environments: List<GenEnvironment>): List<LintFinding> {
        val names = environments.map { it.name }.toSet()
        val claimed = mutableMapOf<String, String>()
        return environments.flatMap { environment ->
            val path = "environments.${environment.name}"
            val aliases =
                environment.aliases.mapNotNull { alias ->
                    val earlier = claimed.putIfAbsent(alias, environment.name)
                    when {
                        alias in names -> LintFinding(path, "alias '$alias' is the name of another environment", "ONT012")
                        earlier != null && earlier != environment.name -> LintFinding(path, "alias '$alias' already names $earlier", "ONT012")
                        earlier != null -> LintFinding(path, "alias '$alias' is listed twice", "ONT012")
                        else -> null
                    }
                }
            aliases + listOfNotNull(LintFinding(path, "missing description", "ONT012").takeIf { environment.description.isNullOrBlank() })
        }
    }

    fun errors(
        findings: List<LintFinding>,
        warnOnly: Boolean,
    ): List<LintFinding> = findings.filter { !(warnOnly && it.code == WARNING) }

    /** A count, then each type's findings under its name, in the order the registry declares them. */
    fun report(
        findings: List<LintFinding>,
        warnOnly: Boolean,
    ): String {
        val errors = errors(findings, warnOnly).size
        return buildString {
            append("Ontology lint: $errors errors, ${findings.size - errors} warnings")
            findings.groupBy { it.group }.forEach { (group, inGroup) ->
                append("\n").append(group)
                inGroup.forEach { append("\n  ").append(it.line) }
            }
        }
    }

    private fun typeFindings(
        type: GenNodeType,
        path: String,
    ): List<LintFinding> =
        listOfNotNull(
            LintFinding(path, "missing description", "ONT001").takeIf { type.description.isNullOrBlank() },
            LintFinding(path, "no example node", "ONT004").takeIf { type.examples.isEmpty() },
            LintFinding(path, "a core type names no questions it helps answer", "ONT005")
                .takeIf { type.name in CORE_TYPES && type.questions.isEmpty() },
        )

    private fun edgeFindings(
        type: GenEdgeType,
        path: String,
        nodeNames: Set<String>,
    ): List<LintFinding> =
        listOfNotNull(LintFinding(path, "missing description", "ONT010").takeIf { type.description.isNullOrBlank() }) +
            listOf("from" to type.from, "to" to type.to).flatMap { (end, names) ->
                names.filter { it !in nodeNames }.map { LintFinding(path, "$end names undeclared node type '$it'", "ONT010") }
            }

    @Suppress("LongParameterList")
    private fun propertyFindings(
        property: GenProperty,
        path: String,
        owner: String,
        siblings: List<GenProperty>,
        edgeNames: Set<String>,
    ): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        val description = property.description
        if (description.isNullOrBlank()) {
            findings += LintFinding(path, "missing description", "ONT001")
        } else {
            if (description.trim().length < MIN_DESCRIPTION) findings += LintFinding(path, "description is under 20 characters", WARNING)
            if (description.trim().trimEnd('.').equals(property.name, ignoreCase = true)) {
                findings += LintFinding(path, "description only repeats the property name", WARNING)
            }
        }
        if (property.examples.isEmpty()) findings += LintFinding(path, "missing examples", "ONT002")
        property.examples.forEach { example ->
            ExampleValidator.problems(property, example).forEach {
                findings += LintFinding(path, "example ${JSON.writeValueAsString(example)} $it", "ONT003")
            }
        }
        findings += enumFindings(property.enum.orEmpty(), path)
        property.format?.takeIf { it !in FormatRules.KNOWN }?.let { findings += LintFinding(path, "unknown format '$it'", "ONT007") }
        property.formatWhen.keys.filter { key -> siblings.none { it.name == key } }.forEach {
            findings += LintFinding(path, "formatWhen names '$it', which the type does not declare", "ONT007")
        }
        property.deprecated?.replacedBy?.let { replacement ->
            if (siblings.none { it.name == replacement } && replacement !in edgeNames) {
                findings +=
                    LintFinding(
                        path,
                        "deprecated in favour of '$replacement', which is neither a property of $owner nor an edge type",
                        "ONT008",
                    )
            }
        }
        return findings
    }

    private fun enumFindings(
        values: List<String>,
        path: String,
    ): List<LintFinding> {
        if (values.isEmpty()) return emptyList()
        val duplicates =
            values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map {
                LintFinding(path, "enum value '$it' appears more than once", "ONT006")
            }
        val conventions = listOf(SNAKE, KEBAB, UPPER_SNAKE)
        val outside =
            values.distinct().filter { value -> conventions.none { it.matches(value) } }.map {
                LintFinding(path, "enum value '$it' is not snake_case, kebab-case or UPPER_SNAKE_CASE", "ONT006")
            }
        val conforming = values.distinct().filter { value -> conventions.any { it.matches(value) } }
        val shared = conventions.filter { convention -> conforming.all { convention.matches(it) } }
        val mixed =
            if (conforming.isNotEmpty() && shared.isEmpty()) {
                listOf(LintFinding(path, "enum mixes naming conventions: ${conforming.joinToString()}", "ONT006"))
            } else {
                emptyList()
            }
        return duplicates + outside + mixed
    }

    private fun exampleFindings(
        example: Map<String, Any?>,
        path: String,
        properties: List<GenProperty>,
    ): List<LintFinding> {
        val declared = properties.associateBy { it.name }
        val missing =
            properties.filter { it.required && example[it.name] == null }.map {
                LintFinding(path, "example node lacks required property ${it.name}", "ONT003")
            }
        val undeclared =
            example.keys.filter { it !in declared }.map {
                LintFinding(path, "example node sets $it, which the type does not declare", "ONT003")
            }
        val invalid =
            example.entries.filter { it.key in declared && it.value != null }.flatMap { (name, value) ->
                ExampleValidator.problems(declared.getValue(name), value, example).map {
                    LintFinding(path, "example node's $name ${JSON.writeValueAsString(value)} $it", "ONT003")
                }
            }
        return missing + undeclared + invalid
    }
}
