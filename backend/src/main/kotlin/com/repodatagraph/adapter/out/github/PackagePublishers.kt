package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.PublishedPackage

/**
 * Which repository publishes which package (#86, FR-4), for resolving a dependency by name.
 *
 * Names are compared case-insensitively, as npm and Maven coordinates are in practice. A name two
 * repositories publish resolves to neither: picking one would be a coin toss recorded as a dependency,
 * and an unresolved name is recorded as the library it says it is, which is at least true.
 */
class PackagePublishers private constructor(
    private val byName: Map<String, NodeKey?>,
) {
    fun publisherOf(packageName: String): NodeKey? = byName[packageName.lowercase()]

    private val names: Set<String> get() = byName.keys

    companion object {
        val NONE = PackagePublishers(emptyMap())

        fun of(entries: List<Pair<String, NodeKey>>): PackagePublishers =
            PackagePublishers(
                entries
                    .groupBy({ it.first.lowercase() }, { it.second })
                    .mapValues { (_, publishers) -> publishers.distinct().singleOrNull() },
            )

        fun fromGraph(packages: List<PublishedPackage>): PackagePublishers = of(packages.map { it.name to it.publisher })

        /**
         * What this [run] read, and what the [graph] remembers of everything else.
         *
         * The run wins for any name it saw. The graph answers for the rest, except where it only
         * remembers a repository this run read - [readInRun] - since that repository has just said what
         * it publishes now, and the graph's memory of it is out of date.
         */
        fun combine(
            run: PackagePublishers,
            graph: PackagePublishers,
            readInRun: Set<NodeKey>,
        ): PackagePublishers {
            val remembered =
                graph.byName.filterKeys { it !in run.names }.filterValues { publisher -> publisher == null || publisher !in readInRun }
            return PackagePublishers(remembered + run.byName)
        }
    }
}
