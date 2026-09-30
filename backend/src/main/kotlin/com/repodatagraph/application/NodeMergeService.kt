package com.repodatagraph.application

import com.repodatagraph.domain.exception.InvalidMergeException
import com.repodatagraph.domain.exception.ManagedNodeTypeException
import com.repodatagraph.domain.exception.MergeConflictException
import com.repodatagraph.domain.exception.MergeIntoRetiredException
import com.repodatagraph.domain.exception.NodeAlreadyMergedException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.MergeOutcome
import com.repodatagraph.domain.identity.MergePlan
import com.repodatagraph.domain.identity.MergeRules
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.NodeMergeUseCase
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.NodeMergeStore
import com.repodatagraph.observability.LogEvents
import org.springframework.stereotype.Service

/**
 * Merging one node into another of its type found to be the same thing (#98, FR-4 and FR-5).
 *
 * Every refusal is decided here, before the store is asked to do anything: a merge into itself or
 * across types, of the graph's own records, of a node merged already, into a retired node, or of two
 * nodes the registry says differ. What is left is a plan in which the node merged into wins every
 * disagreement, handed to the store to carry out in one transaction, and recorded as whoever asked -
 * a principal through the API, or the write whose digest showed two artifacts are one.
 */
@Service
class NodeMergeService(
    private val registry: OntologyRegistry,
    private val identityResolver: IdentityResolver,
    private val derivedProperties: DerivedProperties,
    private val graphStore: GraphStore,
    private val mergeStore: NodeMergeStore,
    private val statedProvenance: StatedProvenance,
) : NodeMergeUseCase,
    AutomaticMerge {
    override fun merge(
        type: String,
        key: String,
        into: String,
        dryRun: Boolean,
    ): MergeOutcome {
        val nodeType = mergeable(type)
        val from = NodeKey(type, key.removePrefix("$type:"))
        val target = targetKey(type, into)
        if (target == from) throw InvalidMergeException("a node cannot be merged into itself")
        if (target.type != type) throw InvalidMergeException("a $type cannot be merged into a ${target.type}")
        // Who is asking, before anything is read: FR-5 records the merge as them.
        val by = statedProvenance.forWrite(Provenance.MANUAL)
        return carryOut(nodeType, from, target, by, dryRun, automatic = false)
    }

    /**
     * Merges [from] into [into] as a write showed they are one (#98, FR-2), recorded as [by], that
     * write's provenance. The same refusals hold as for a merge a principal asks for.
     */
    fun mergeAutomatically(
        from: NodeKey,
        into: NodeKey,
        by: Provenance,
    ): MergeOutcome = carryOut(mergeable(from.type), from, into, by, dryRun = false, automatic = true)

    /**
     * A fold the graph refuses now - another writer folded it first, or retired either side - is
     * skipped rather than failing the write that found it, which stands on its own.
     */
    override fun fold(
        from: NodeKey,
        into: NodeKey,
        by: Provenance,
    ) {
        try {
            mergeAutomatically(from, into, by)
        } catch (refused: NodeAlreadyMergedException) {
            skipped(from, into, refused)
        } catch (refused: MergeIntoRetiredException) {
            skipped(from, into, refused)
        } catch (refused: MergeConflictException) {
            skipped(from, into, refused)
        } catch (refused: NodeNotFoundException) {
            skipped(from, into, refused)
        }
    }

    private fun skipped(
        from: NodeKey,
        into: NodeKey,
        refused: RuntimeException,
    ) = LogEvents.nodeMergeSkipped(from.type, from.key, into.key, refused.message.orEmpty())

    private fun carryOut(
        nodeType: NodeTypeDef,
        from: NodeKey,
        into: NodeKey,
        by: Provenance,
        dryRun: Boolean,
        automatic: Boolean,
    ): MergeOutcome {
        val source = graphStore.findNode(from)
        val target = graphStore.findNode(into)
        if (source == null || target == null) {
            throw NodeNotFoundException(listOfNotNull(from.takeIf { source == null }, into.takeIf { target == null }))
        }
        refusal(nodeType, source, target)?.let { throw it }

        val plan =
            MergePlan(
                source = source,
                target = target,
                gained = gained(nodeType, source, target),
                kept = MergeRules.kept(nodeType, source, target),
                released = MergeRules.released(nodeType, source, target),
                previousKeys = MergeRules.previousKeys(source, target),
                by = by,
            )
        val result = mergeStore.merge(plan, dryRun)
        if (!dryRun) {
            LogEvents.nodeMerged(nodeType.name, from.key, into.key, result.edges.moved, automatic, by.writtenBy ?: by.sourceSystem)
        }
        return MergeOutcome(
            from = from,
            into = into,
            dryRun = dryRun,
            node = result.node,
            gained = plan.gained.keys.toList(),
            kept = plan.kept,
            edges = result.edges,
            previousKeys = plan.previousKeys,
            redirected = result.redirected,
        )
    }

    /**
     * Why [source] may not be merged into [target], checked in this order, or null: it was merged
     * already, the target is retired, or the two disagree on their merge scope or alias.
     */
    private fun refusal(
        nodeType: NodeTypeDef,
        source: GraphNode,
        target: GraphNode,
    ): RuntimeException? {
        val mergedInto = if (source.provenance.current) null else graphStore.mergedInto(source.key)
        val conflicts = MergeRules.conflicts(nodeType, source, target)
        return when {
            mergedInto != null -> NodeAlreadyMergedException(source.key, mergedInto)
            !target.provenance.current -> MergeIntoRetiredException(target.key)
            conflicts.isNotEmpty() -> MergeConflictException(conflicts)
            else -> null
        }
    }

    /**
     * What the target takes from the source, with what the server derives derived again from the
     * target's values rather than copied, and without any value that would move the target's key: a
     * merge keeps the key of the node merged into, whatever the source held.
     */
    private fun gained(
        nodeType: NodeTypeDef,
        source: GraphNode,
        target: GraphNode,
    ): Map<String, Any?> {
        val accepted =
            MergeRules.fills(nodeType, source, target).entries.fold(emptyMap<String, Any?>()) { taken, (name, value) ->
                val trial = taken + (name to value)
                if (keyOf(target, trial) == target.key) trial else taken
            }
        val merged = derivedProperties.expand(nodeType.name, target.props + accepted)
        return merged.filter { (name, value) -> value != null && target.props[name] != value && nodeType.property(name) != null }
    }

    private fun keyOf(
        target: GraphNode,
        fills: Map<String, Any?>,
    ): NodeKey? =
        runCatching {
            identityResolver.keyFor(target.type, derivedProperties.expand(target.type, target.props + fills))
        }.getOrNull()

    /**
     * A declared type whose nodes describe the software rather than the graph. The graph's own
     * records, such as a sync run or an ontology version, are never duplicates of one another.
     */
    private fun mergeable(type: String): NodeTypeDef {
        val nodeType = registry.nodeType(type) ?: throw NodeTypeNotFoundException(type)
        val refused =
            when {
                type == ServicePrincipal.NODE_TYPE -> ManagedNodeTypeException(type, ServicePrincipal.API_PATH)
                type in LIFECYCLE_MANAGED -> ManagedNodeTypeException(type, LIFECYCLE_API_PATH)
                nodeType.meta -> InvalidMergeException("a $type is one of the graph's own records, and is never merged")
                else -> null
            }
        return refused?.let { throw it } ?: nodeType
    }

    /** [into] as a key of [type], or as a full `Type:key` id of whichever type it names. */
    private fun targetKey(
        type: String,
        into: String,
    ): NodeKey {
        val named = into.substringBefore(':', missingDelimiterValue = "")
        return if (named.isNotEmpty() && registry.nodeType(named) != null) {
            NodeKey(named, into.removePrefix("$named:"))
        } else {
            NodeKey(type, into)
        }
    }

    private companion object {
        val LIFECYCLE_MANAGED = setOf("NodeVersion", "OntologyMigration")
        const val LIFECYCLE_API_PATH = "/api/v1/lifecycle"
    }
}
