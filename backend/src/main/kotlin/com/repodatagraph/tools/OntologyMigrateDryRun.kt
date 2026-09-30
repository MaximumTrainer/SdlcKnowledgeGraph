package com.repodatagraph.tools

import com.repodatagraph.adapter.out.ontology.ClasspathMigrationSource
import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.application.lifecycle.MigrationPlanner
import com.repodatagraph.application.lifecycle.YamlMigrationCompiler
import com.repodatagraph.config.lifecycle.LifecycleProperties
import com.repodatagraph.domain.lifecycle.InvalidMigrationException
import com.repodatagraph.domain.lifecycle.OntologyVersion
import org.springframework.core.io.DefaultResourceLoader
import kotlin.system.exitProcess

/**
 * `./gradlew ontologyMigrateDryRun [-Pfrom=1.3.0] [-Pmigrations=classpath:...]` (#33, FR7): prints
 * what each shipped migration would run, statement by statement, without connecting to anything.
 *
 * With `from`, the version a graph is on, it says which migrations that graph would have run and
 * which it would record as baselines; without it, every migration is shown as it would run. A
 * migration that cannot compile - misnamed, or naming what the registry does not declare - fails the
 * task, as it would fail the application's startup.
 */
fun main(args: Array<String>) {
    val options =
        args
            .mapNotNull { arg ->
                arg.removePrefix("--").split("=", limit = 2).takeIf { it.size == 2 }
            }.associate { it[0] to it[1] }
    val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    val location = options["location"]?.takeIf { it.isNotBlank() } ?: LifecycleProperties.DEFAULT_LOCATION
    val from = options["from"]?.takeIf { it.isNotBlank() }?.let(OntologyVersion::parse)
    val compiler = YamlMigrationCompiler(registry)

    try {
        val files = ClasspathMigrationSource(location, DefaultResourceLoader()).migrations()
        val plan = MigrationPlanner.plan(files, OntologyVersion.parse(registry.version), from ?: OntologyVersion(0, 0, 0), emptyList())
        println("ontology ${registry.version}; migrations from $location; graph on ${from ?: "any older version"}")
        if (files.isEmpty()) println("no migrations are shipped: a graph on an older version is only recorded on ${registry.version}")
        files.forEach { file ->
            val fate = if (file in plan.baseline) "baseline (not run: the graph is already on or past it)" else "would run"
            println()
            println("${file.id}  [$fate]")
            println("  checksum ${file.checksum}")
            compiler.description(file)?.let { println("  $it") }
            compiler.compile(file).forEachIndexed { index, statement ->
                println("  ${index + 1}. ${statement.cypher.replace("\n", "\n     ")}")
                if (statement.parameters.isNotEmpty()) println("     parameters ${statement.parameters}")
            }
        }
    } catch (invalid: InvalidMigrationException) {
        System.err.println(invalid.message)
        exitProcess(1)
    }
}
