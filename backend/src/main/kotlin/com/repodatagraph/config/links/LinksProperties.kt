package com.repodatagraph.config.links

import com.repodatagraph.application.links.rules.TagLinkRule
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * `links.*`: the link engine's threshold, its rules' confidences, when it runs on its own, and which
 * connectors' runs it follows (#28).
 */
@ConfigurationProperties(prefix = "links")
data class LinksProperties(
    @DefaultValue("0.5") val threshold: Double = DEFAULT_THRESHOLD,
    @DefaultValue(DEFAULT_SCHEDULE) val schedule: String = DEFAULT_SCHEDULE,
    @DefaultValue("github,github-actions,aws,azure,gcp") val triggers: List<String> = DEFAULT_TRIGGERS,
    @DefaultValue val rules: Rules = Rules(),
) {
    init {
        require(threshold in 0.0..1.0) { "links.threshold must be between 0 and 1, was $threshold" }
    }

    data class Rules(
        @DefaultValue val manual: Rule = Rule(),
        @DefaultValue val tag: TagRule = TagRule(),
        @DefaultValue val deployment: Rule = Rule(),
        @DefaultValue val iac: Rule = Rule(),
        @DefaultValue val naming: Rule = Rule(),
    )

    /** A rule's confidence, or null for the rule's own default from the plan's table, and whether it runs. */
    data class Rule(
        val confidence: Double? = null,
        @DefaultValue("true") val enabled: Boolean = true,
    )

    data class TagRule(
        @DefaultValue("0.95") val confidence: Double = TagLinkRule.DEFAULT_CONFIDENCE,
        @DefaultValue("true") val enabled: Boolean = true,
        @DefaultValue("repo,repository,source-repo,git-repo") val keys: List<String> = TagLinkRule.DEFAULT_KEYS,
    )

    companion object {
        const val DEFAULT_THRESHOLD = 0.5

        /** Nightly, an hour before the archive, so the archive sees what the night resolved. */
        const val DEFAULT_SCHEDULE = "0 0 3 * * *"
        val DEFAULT_TRIGGERS = listOf("github", "github-actions", "aws", "azure", "gcp")
    }
}
