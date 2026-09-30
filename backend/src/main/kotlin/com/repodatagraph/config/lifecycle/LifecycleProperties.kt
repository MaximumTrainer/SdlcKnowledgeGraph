package com.repodatagraph.config.lifecycle

import com.repodatagraph.domain.lifecycle.ArchiveMode
import com.repodatagraph.domain.lifecycle.ArchiveSettings
import com.repodatagraph.domain.lifecycle.MigrationMode
import com.repodatagraph.domain.lifecycle.VersioningSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * `lifecycle.*` (#33): versioning, the archive and the ontology migrations. Every default is the
 * safe one: versions kept, the archive off, and a dry run when it is on.
 */
@ConfigurationProperties("lifecycle")
data class LifecycleProperties(
    @DefaultValue val versioning: Versioning = Versioning(),
    @DefaultValue val archive: Archive = Archive(),
    @DefaultValue val migrations: Migrations = Migrations(),
) {
    data class Versioning(
        @DefaultValue("true") val enabled: Boolean = true,
        @DefaultValue("50") val maxVersions: Int = VersioningSettings.DEFAULT_MAX_VERSIONS,
        @DefaultValue val excludeTypes: List<String> = emptyList(),
    ) {
        fun settings() = VersioningSettings(enabled, maxVersions, excludeTypes)
    }

    data class Archive(
        @DefaultValue("false") val enabled: Boolean = false,
        @DefaultValue("dry-run") val mode: String = "dry-run",
        @DefaultValue("P365D") val retention: Duration = Duration.ofDays(ArchiveSettings.DEFAULT_RETENTION_DAYS),
        @DefaultValue(ArchiveSettings.DEFAULT_SCHEDULE) val schedule: String = ArchiveSettings.DEFAULT_SCHEDULE,
        @DefaultValue("./archive") val directory: String = "./archive",
    ) {
        fun settings() = ArchiveSettings(enabled, ArchiveMode.fromWire(mode), retention, schedule, directory)
    }

    data class Migrations(
        @DefaultValue("auto") val mode: String = "auto",
        @DefaultValue(DEFAULT_LOCATION) val location: String = DEFAULT_LOCATION,
    ) {
        fun migrationMode(): MigrationMode = MigrationMode.fromWire(mode)
    }

    companion object {
        const val DEFAULT_LOCATION = "classpath:ontology/migrations/"
    }
}
