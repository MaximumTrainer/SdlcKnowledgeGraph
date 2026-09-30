package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.port.out.MigrationSource
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The migration files shipped under [location], `classpath:ontology/migrations/` unless configured
 * otherwise (#33, FR7). A `.yaml` or `.cypher` file there must be named as a migration, and is
 * refused by name when it is not; anything else in the directory is not a migration and is skipped.
 */
class ClasspathMigrationSource(
    private val location: String,
    resourceLoader: ResourceLoader,
) : MigrationSource {
    private val resolver = PathMatchingResourcePatternResolver(resourceLoader)

    override fun migrations(): List<MigrationFile> {
        val directory = if (location.endsWith("/")) location else "$location/"
        return resolver
            .getResources("$directory*")
            .filter { it.isReadable && it.filename != null }
            .filter { resource -> EXTENSIONS.any { resource.filename!!.endsWith(it) } }
            .map { MigrationFile.parse(it.filename!!, it.getContentAsString(Charsets.UTF_8)) }
            .sortedBy { it.version }
    }

    private companion object {
        val EXTENSIONS = listOf(".yaml", ".cypher")
    }
}
