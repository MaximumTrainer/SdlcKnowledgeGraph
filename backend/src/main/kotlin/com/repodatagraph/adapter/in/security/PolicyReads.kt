package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.domain.policy.FilterItem
import com.repodatagraph.domain.policy.FilterRequest
import com.repodatagraph.domain.policy.FilterResult
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import com.repodatagraph.observability.LogEvents
import org.springframework.security.core.context.SecurityContextHolder

/**
 * The policy's filter over what a read returns (#30 FR4, FR5), for the subject of the current request:
 * which nodes they may see, and which properties to take out of each. REST ([PolicyResponseAdvice])
 * and GraphQL ([com.repodatagraph.adapter.in.graphql.AsOfResolver]) both filter through here, so a
 * node is hidden, or a property redacted, the same way whichever API reads it.
 *
 * A filter that hid or redacted anything is logged as `policy.redacted`, naming the subject, how many
 * nodes were left out and which properties were taken out. One that changed nothing is not logged.
 */
class PolicyReads(
    private val policy: PolicyDecisionPoint,
    private val properties: PolicyProperties,
) {
    fun filter(items: Collection<FilterItem>): FilterResult {
        if (items.isEmpty()) return FilterResult.EMPTY
        val subject = PolicySubjects.of(SecurityContextHolder.getContext().authentication, properties)
        val result = policy.filter(FilterRequest(subject, items.distinctBy { it.id }))
        val redacted =
            result.allowed.values
                .flatten()
                .distinct()
                .sorted()
        if (result.denied.isNotEmpty() || redacted.isNotEmpty()) {
            LogEvents.policyRedacted(subject.id, result.denied.size, redacted)
        }
        return result
    }

    /** [props] without the properties [result] redacts from node [id]. */
    fun redact(
        result: FilterResult,
        id: String,
        props: Map<String, Any?>,
    ): Map<String, Any?> {
        val redactions = result.redactions(id)
        return if (redactions.isEmpty()) props else props - redactions.toSet()
    }
}
