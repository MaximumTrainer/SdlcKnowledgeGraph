package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkContext
import com.repodatagraph.application.links.LinkProposal
import com.repodatagraph.application.links.LinkRule
import com.repodatagraph.domain.model.GraphNode

/**
 * The infrastructure-as-code rule (#28, FR2): a file in a repository names the resource literally -
 * its ARN or id, its name, or a Terraform address whose local name is the resource's name with
 * underscores for hyphens. A claim that the resource should exist, so weaker than a tag but stronger
 * than a name that merely matches. One proposal per current file that names it, in path order.
 */
class IacLinkRule(
    private val confidence: Double = DEFAULT_CONFIDENCE,
) : LinkRule {
    override val name = "iac"

    override fun evaluate(
        resource: GraphNode,
        ctx: LinkContext,
    ): List<LinkProposal> {
        val resourceId = resource.props["resourceId"]?.toString()
        val resourceName = resource.props["name"]?.toString()
        return ctx
            .iacFiles()
            .filter { it.provenance.current }
            .sortedBy { it.props["path"]?.toString().orEmpty() }
            .mapNotNull { file ->
                val repoKey = file.props["repoKey"]?.toString() ?: return@mapNotNull null
                if (ctx.repository(repoKey) == null) return@mapNotNull null
                val refs = (file.props["resourceRefs"] as? Collection<*>).orEmpty().map { it.toString() }
                refs.firstNotNullOfOrNull { ref -> match(ref, resourceId, resourceName)?.let { ref to it } }?.let { (ref, matched) ->
                    LinkProposal(
                        repoKey,
                        confidence,
                        name,
                        mapOf("path" to file.props["path"].toString(), "reference" to ref, "matched" to matched),
                    )
                }
            }
    }

    private fun match(
        ref: String,
        resourceId: String?,
        resourceName: String?,
    ): String? =
        when {
            resourceId != null && ref.equals(resourceId, ignoreCase = true) -> "resourceId"
            resourceName != null && ref.equals(resourceName, ignoreCase = true) -> "name"
            resourceName != null && terraformLocalName(ref)?.let { sameName(it, resourceName) } == true -> "terraform"
            else -> null
        }

    private fun terraformLocalName(ref: String): String? = TERRAFORM_ADDRESS.matchEntire(ref)?.groupValues?.get(1)

    private fun sameName(
        local: String,
        resourceName: String,
    ) = local.replace('_', '-').equals(resourceName.replace('_', '-'), ignoreCase = true)

    companion object {
        const val DEFAULT_CONFIDENCE = 0.7

        /** `aws_s3_bucket.acme_payments_logs`: a resource type, a dot, and the local name. */
        private val TERRAFORM_ADDRESS = Regex("""^[a-z][a-z0-9_]*\.([A-Za-z0-9_\-]+)$""")
    }
}
