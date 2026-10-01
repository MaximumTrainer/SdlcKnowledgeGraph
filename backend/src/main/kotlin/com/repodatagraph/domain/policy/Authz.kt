package com.repodatagraph.domain.policy

import java.time.Instant

/**
 * One subject model for users, connectors and agents (#30 FR1, ADR-0020): who is asking, and what
 * the policy may judge them by.
 *
 * @param id the subject as provenance names it: a user's token subject, a service principal's
 *   registered name, or `anonymous` on an instance with no identity provider
 * @param scopes the graph scopes on the token (#116), the ceiling of anything a role allows
 * @param roles the roles the token's roles claim carries, or null when it carries none: a subject
 *   without the claim is judged by its scopes alone, as every caller was before roles existed
 * @param teams the keys of the teams the subject belongs to: the identity provider's groups, and for
 *   a service principal the team that owns it
 */
data class Subject(
    val id: String,
    val kind: SubjectKind,
    val scopes: Set<String> = emptySet(),
    val roles: List<String>? = null,
    val teams: Set<String> = emptySet(),
    val attributes: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** Whoever reads an instance with no identity provider (#118). */
        val ANONYMOUS = Subject("anonymous", SubjectKind.ANONYMOUS)
    }
}

/** What kind of caller a subject is. An agent is a service principal registered as one (#30). */
enum class SubjectKind(
    val wireName: String,
) {
    USER("user"),
    SERVICE("service"),
    AGENT("agent"),
    ANONYMOUS("anonymous"),
}

/** What a request does, as the policy decides it (#30 FR1). */
enum class AuthzAction(
    val wireName: String,
) {
    READ("read"),

    /** A read whose input is a body, sent as a POST (#87): impact, context packs, policy questions. */
    QUERY("query"),
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),

    /** Stating, accepting or removing a relationship (#28). */
    LINK("link"),

    /** Asking a connector to sync now. */
    SYNC("sync"),

    /** Administering the graph as a whole (#33, #98): migrations, the archive, merging nodes. */
    ADMIN("admin"),
    ;

    companion object {
        fun fromWireName(value: String): AuthzAction? = entries.firstOrNull { it.wireName == value }
    }
}

/** What kind of thing a request acts on. */
enum class ResourceKind(
    val wireName: String,
) {
    /** Nodes of one type, or one of them. */
    NODE("node"),

    /** Relationships. */
    EDGE("edge"),

    /** A route that answers with a mixture, judged as a whole. */
    ENDPOINT("endpoint"),

    /** The policy itself: its status, and questions put to it. */
    POLICY("policy"),
}

/**
 * What a request acts on (#30 FR1).
 *
 * @param filtered for a read, whether what it returns is safe to give a subject not cleared for
 *   everything: it passes through the policy's filter, so that nodes and properties the subject may
 *   not see are removed from it, or it holds nothing the registry labels. A read that is neither is
 *   answered only to a subject cleared for everything (sensitivity.rego).
 * @param ownerTeams the keys of the teams that own the node, looked up only when owning it could
 *   change the answer
 * @param provenance the provenance a write states, for the rule about systems of record
 */
data class AuthzResource(
    val kind: ResourceKind,
    val type: String? = null,
    val key: String? = null,
    val filtered: Boolean = false,
    val ownerTeams: Set<String> = emptySet(),
    val provenance: StatedProvenanceClaim? = null,
)

/** The provenance a write would be recorded with (#95 FR-3): its source, how sure, and whether inferred. */
data class StatedProvenanceClaim(
    val sourceSystem: String,
    val confidence: Double = 1.0,
    val inferred: Boolean = false,
)

/** A question for the policy: may [subject] do [action] to [resource]? */
data class AuthzRequest(
    val subject: Subject,
    val action: AuthzAction,
    val resource: AuthzResource,
    val context: Map<String, Any?> = emptyMap(),
)

/**
 * The policy's answer (#30 FR1, #95 FR-1).
 *
 * @param policy the rule that refused it (`scopes`, `roles`, `agents`, `sensitivity`,
 *   `provenance.confidence`, ...), or `allow`
 * @param required the graph scopes the action needs, whether or not the token holds them
 * @param redact the properties to remove from a node of the resource's type before it is shown
 * @param clearance the most sensitive level the subject may see
 */
data class Decision(
    val allow: Boolean,
    val policy: String,
    val reason: String,
    val required: List<String> = emptyList(),
    val redact: List<String> = emptyList(),
    val clearance: String = "",
) {
    /** A refusal for want of a scope: answered with the 403 the API has always given (docs/AUTH.md). */
    val refusedForScope: Boolean get() = !allow && policy == SCOPES

    companion object {
        const val SCOPES = "scopes"
        const val PROVENANCE = "provenance.confidence"
    }
}

/** One node in a list a read returns, as the filter sees it: its id and its type. */
data class FilterItem(
    val id: String,
    val type: String,
)

/** Which nodes of a read [subject] may see (#30 FR5), decided in one evaluation. */
data class FilterRequest(
    val subject: Subject,
    val items: List<FilterItem>,
    val context: Map<String, Any?> = emptyMap(),
)

/** The filter's answer: each visible id with the properties to remove from it, and the ids to leave out. */
data class FilterResult(
    val allowed: Map<String, List<String>>,
    val denied: List<String>,
) {
    fun visible(id: String): Boolean = id in allowed

    fun redactions(id: String): List<String> = allowed[id].orEmpty()

    /** True when every node is visible and nothing is taken out of any: the read goes out as it is. */
    val changesNothing: Boolean get() = denied.isEmpty() && allowed.values.all { it.isEmpty() }

    companion object {
        val EMPTY = FilterResult(emptyMap(), emptyList())
    }
}

/**
 * A graph fact as the agent-actions policy sees it (#95 FR-4): what the graph holds about an id an
 * agent cited, never what the agent said about it.
 *
 * @param found false when the graph holds nothing by that id; nothing else is then known
 * @param ageMinutes whole minutes since the source observed the fact
 * @param priorArtifact for a deployment, whether an earlier, different artifact was deployed to the
 *   same environment, so there is something to roll back to
 */
data class AgentFact(
    val id: String,
    val found: Boolean,
    val kind: ResourceKind? = null,
    val type: String? = null,
    val confidence: Double? = null,
    val inferred: Boolean? = null,
    val ageMinutes: Long? = null,
    val priorArtifact: Boolean? = null,
    val environment: String? = null,
)

/** The agent-actions policy's answer: whether the agent may act, and why. */
data class AgentActionDecision(
    val allow: Boolean,
    val policy: String,
    val action: String,
    val reasons: List<String>,
)

/**
 * Which policy is in force (#30 FR8): the bundle's revision, when this instance loaded it, what
 * evaluates it, and what happens when it cannot be evaluated.
 */
data class PolicyStatus(
    val name: String,
    val revision: String,
    val loadedAt: Instant,
    val engine: String,
    val status: String,
    val failMode: String,
    val source: String,
)
