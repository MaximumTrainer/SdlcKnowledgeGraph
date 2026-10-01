package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.PublishedPackage
import com.repodatagraph.domain.port.out.connector.PublishedPackageIndex
import org.springframework.stereotype.Component

/**
 * What the graph already knows each repository publishes, read from `Repository.packageNames` (#86,
 * FR-4).
 *
 * Read a page at a time in key order, as every walk over a type is: an estate of thousands of
 * repositories is one query per page rather than one per repository, and what is kept is only the
 * names and keys. A retired repository publishes nothing any more, and a fork is left out unless
 * asked for, because it carries its upstream's manifest and would claim its upstream's names.
 */
@Component
class GraphPublishedPackageIndex(
    private val graphStore: GraphStore,
) : PublishedPackageIndex {
    override fun publishedPackages(includeForks: Boolean): List<PublishedPackage> {
        val published = mutableListOf<PublishedPackage>()
        var after: String? = null
        do {
            val page = graphStore.findNodes(REPOSITORY, emptyMap(), after, PAGE)
            page
                .filter { it.provenance.current }
                .filter { includeForks || it.props[FORK_OF] == null }
                .forEach { repository ->
                    namesIn(repository.props[PACKAGE_NAMES]).forEach { published += PublishedPackage(it, repository.key) }
                }
            after = page.lastOrNull()?.key?.key
        } while (page.size == PAGE)
        return published
    }

    private fun namesIn(value: Any?): List<String> =
        when (value) {
            is Collection<*> -> value.mapNotNull { it?.toString() }
            is Array<*> -> value.mapNotNull { it?.toString() }
            else -> emptyList()
        }

    private companion object {
        const val REPOSITORY = "Repository"
        const val PACKAGE_NAMES = "packageNames"
        const val FORK_OF = "forkOf"
        const val PAGE = 500
    }
}
