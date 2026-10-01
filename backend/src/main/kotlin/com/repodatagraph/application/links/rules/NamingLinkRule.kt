package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkContext
import com.repodatagraph.application.links.LinkProposal
import com.repodatagraph.application.links.LinkRule
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.ontology.EnvironmentAliases

/**
 * The naming rule (#28, FR2): a resource called what a repository is called, or what a package it
 * publishes is called, once the decoration clouds and teams add is taken off - environment, account
 * and region. Names collide, so this is only ever a candidate on its own.
 */
class NamingLinkRule(
    private val confidence: Double = DEFAULT_CONFIDENCE,
) : LinkRule {
    override val name = "naming"

    override fun evaluate(
        resource: GraphNode,
        ctx: LinkContext,
    ): List<LinkProposal> {
        val resourceName = resource.props["name"]?.toString() ?: return emptyList()
        val normalised = normalise(resourceName, ctx.environments)
        return ctx.repositories().sortedBy { it.key.key }.mapNotNull { repository ->
            val matched =
                when {
                    repository.props["name"]?.toString()?.lowercase() == normalised -> "name"
                    packageNames(repository).any { it == normalised } -> "packageName"
                    else -> null
                }
            matched?.let {
                LinkProposal(
                    repository.key.key,
                    confidence,
                    name,
                    mapOf("name" to resourceName, "normalised" to normalised, "matched" to it),
                )
            }
        }
    }

    private fun packageNames(repository: GraphNode): List<String> =
        (repository.props["packageNames"] as? Collection<*>).orEmpty().map { packageName(it.toString()) }

    companion object {
        const val DEFAULT_CONFIDENCE = 0.4

        private val SEPARATORS = Regex("[-_.]+")
        private const val AWS_REGION = """(?:af|ap|ca|cn|eu|il|me|mx|sa|us)(?:-gov)?-[a-z]+-\d"""
        private const val GCP_REGION = """(?:africa|asia|australia|europe|me|northamerica|southamerica|us)-[a-z]+\d"""
        private val REGION_PREFIX = Regex("""^(?:$AWS_REGION|$GCP_REGION)[-_.]""")
        private val REGION_SUFFIX = Regex("""[-_.](?:$AWS_REGION|$GCP_REGION)$""")
        private val ACCOUNT = Regex("""\d{12}""")

        /**
         * [name] lower-cased, without a leading or trailing region or twelve-digit account, and without
         * leading or trailing environment words - the names and aliases of environments.yaml - joined
         * by hyphens. Nothing is taken off that would leave nothing.
         */
        fun normalise(
            name: String,
            environments: EnvironmentAliases,
        ): String {
            val words = environments.asMap().keys + environments.asMap().values
            var text = name.lowercase().trim()
            text = REGION_PREFIX.replace(text, "").let { if (it.isBlank()) text else it }
            text = REGION_SUFFIX.replace(text, "").let { if (it.isBlank()) text else it }
            val tokens = text.split(SEPARATORS).filter { it.isNotEmpty() }.toMutableList()

            fun strippable(token: String) = token in words || ACCOUNT.matches(token)
            while (tokens.size > 1 && strippable(tokens.first())) tokens.removeAt(0)
            while (tokens.size > 1 && strippable(tokens.last())) tokens.removeAt(tokens.lastIndex)
            return tokens.joinToString("-")
        }

        /** A package's own name: `@acme/payments-client` is payments-client, `com.acme:ledger` is ledger. */
        fun packageName(published: String): String = published.substringAfterLast('/').substringAfterLast(':').lowercase()
    }
}
