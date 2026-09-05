package com.repodatagraph.adapter.out.ontology

import com.repodatagraph.domain.model.Artifact
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.Environment
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.ontology.OntologyDriftValidator
import com.repodatagraph.domain.ontology.OntologyRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.KClass

@Configuration
class OntologyConfiguration {
    /**
     * The registry is loaded once at startup and is immutable thereafter. Validation happens in its
     * constructor, so a malformed ontology stops the application here rather than surfacing later as
     * a query that quietly returns nothing.
     */
    @Bean
    fun ontologyRegistry(
        loader: YamlOntologyLoader,
        @Value("\${ontology.path:${YamlOntologyLoader.DEFAULT_BASE_PATH}}") path: String,
    ): OntologyRegistry {
        val registry = loader.load(path)
        OntologyDriftValidator(registry).validate(TYPED_MODEL)
        return registry
    }

    private companion object {
        /**
         * Core types that have a typed Kotlin class as well as a registry entry. Both definitions are
         * checked against each other at startup.
         *
         * `Service` and `ConfigurationItem` are registry-only for now: nothing constructs them yet.
         * `ServiceNowCI` is the reverse, a Kotlin class with no registry entry, because
         * `ConfigurationItem` replaces it in #24; it is deliberately left out of this check until then.
         */
        val TYPED_MODEL: Map<String, KClass<*>> =
            mapOf(
                "Repository" to Repository::class,
                "Team" to Team::class,
                "Pipeline" to Pipeline::class,
                "Artifact" to Artifact::class,
                "Deployment" to Deployment::class,
                "Environment" to Environment::class,
                "CloudResource" to CloudResource::class,
            )
    }
}
