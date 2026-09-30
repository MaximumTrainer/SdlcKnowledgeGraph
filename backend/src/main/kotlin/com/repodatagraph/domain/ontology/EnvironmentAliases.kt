package com.repodatagraph.domain.ontology

/**
 * Which spellings of an environment's name name which environment (#98, FR-3): environments.yaml as
 * the table an Environment's key, and a Deployment's, is derived through.
 *
 * It used to be a map in IdentityResolver, which meant a team spelling its environment differently
 * needed a release. It is declared beside the registry now and validated as it is loaded: an alias
 * that named two environments would split one environment's deployments between two nodes depending
 * on which spelling a writer used, so it stops the application instead.
 */
class EnvironmentAliases(
    environments: List<EnvironmentDef>,
) {
    private val canonicalOf: Map<String, String>

    init {
        val problems = problems(environments)
        if (problems.isNotEmpty()) throw InvalidOntologyException("environments.yaml " + problems.joinToString("; "))
        canonicalOf = environments.flatMap { environment -> environment.aliases.map { it to environment.name } }.toMap(LinkedHashMap())
    }

    /** [name] as an Environment's key: lower-cased and trimmed, and the environment it is an alias of if it is one. */
    fun canonical(name: String): String {
        val normalised = name.lowercase().trim()
        return canonicalOf[normalised] ?: normalised
    }

    /** Every alias with the name it folds into, in declaration order. */
    fun asMap(): Map<String, String> = canonicalOf

    companion object {
        /** No aliases: every name is its own. */
        val NONE = EnvironmentAliases(emptyList())

        private fun problems(environments: List<EnvironmentDef>): List<String> {
            val problems = mutableListOf<String>()
            val names = environments.map { it.name }
            names
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .forEach { problems += "declares environment '$it' more than once" }
            (names + environments.flatMap { it.aliases })
                .filterNot { it.isNotEmpty() && it == it.lowercase().trim() }
                .forEach { problems += "declares '$it', which is not a lower-case, trimmed name as a key is" }
            val claimed = LinkedHashMap<String, String>()
            environments.forEach { environment ->
                environment.aliases.forEach { alias ->
                    val earlier = claimed.putIfAbsent(alias, environment.name)
                    when {
                        earlier != null && earlier != environment.name ->
                            problems += "maps alias '$alias' to both '$earlier' and '${environment.name}'"
                        earlier != null -> problems += "lists alias '$alias' of '${environment.name}' more than once"
                        alias in names ->
                            problems +=
                                "maps alias '$alias' to '${environment.name}', but '$alias' is an environment's own name"
                    }
                }
            }
            return problems
        }
    }
}
