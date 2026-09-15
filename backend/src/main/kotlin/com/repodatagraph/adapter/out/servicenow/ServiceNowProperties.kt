package com.repodatagraph.adapter.out.servicenow

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.net.URI

/** How the connector proves who it is. */
enum class ServiceNowAuthMode {
    BASIC,
    OAUTH,
}

/**
 * Credentials for the instance.
 *
 * Two modes because both are real: an integration user with basic auth is what most instances start
 * with, and OAuth client credentials is what a platform team moves to. Neither is a default the
 * other can stand in for, so the mode is stated rather than inferred from which fields are set.
 */
data class ServiceNowAuth(
    @DefaultValue("BASIC") val mode: ServiceNowAuthMode = ServiceNowAuthMode.BASIC,
    @DefaultValue("") val username: String = "",
    @DefaultValue("") val password: String = "",
    @DefaultValue("") val clientId: String = "",
    @DefaultValue("") val clientSecret: String = "",
)

/**
 * Which instance the connector reads, and how much of it.
 *
 * The lookback windows exist because a CMDB's change and incident tables are unbounded history. A
 * first sync with no watermark would otherwise read every change ever raised, which on a real
 * instance is years of rows nobody is going to ask about.
 *
 * @param instanceName what the instance is called in a key; defaults to the host of [instanceUrl]
 * @param ciTables the CI classes worth reading; a CMDB has hundreds of tables and most of them
 *   describe hardware this graph has no use for
 * @param repoUrlField the custom field an organisation put a repository URL in, if it did
 * @param dependencyRelTypes which `cmdb_rel_ci` types mean a dependency, by their display value
 */
@ConfigurationProperties("connectors.servicenow")
data class ServiceNowProperties(
    @DefaultValue("") val instanceUrl: String = "",
    @DefaultValue("") val instanceName: String = "",
    @DefaultValue val auth: ServiceNowAuth = ServiceNowAuth(),
    // No @DefaultValue on the lists: it binds a comma-separated default as one element rather than
    // splitting it, so the connector would ask for a table called "a,b,c".
    val ciTables: List<String> = DEFAULT_CI_TABLES,
    @DefaultValue(DEFAULT_REPO_FIELD) val repoUrlField: String = DEFAULT_REPO_FIELD,
    val dependencyRelTypes: List<String> = DEFAULT_REL_TYPES,
    @DefaultValue("$DEFAULT_PAGE_SIZE") val pageSize: Int = DEFAULT_PAGE_SIZE,
    @DefaultValue("$DEFAULT_LOOKBACK_DAYS") val changeLookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
    @DefaultValue("$DEFAULT_LOOKBACK_DAYS") val incidentLookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
) {
    /**
     * What the instance is called, which is part of every key this connector writes.
     *
     * Two ServiceNow instances - a production one and a sandbox - number their rows independently, so
     * a key on `sys_id` alone would merge a test CI with a live one.
     *
     * Defaults to the host of [instanceUrl], and can be set explicitly because how an instance is
     * reached is not the same as what it is: putting it behind a new vanity domain would otherwise
     * re-key every configuration item in the graph.
     */
    val instance: String
        get() =
            instanceName.ifBlank {
                runCatching { URI.create(instanceUrl).host }.getOrNull().orEmpty().ifBlank { instanceUrl }
            }

    fun isConfigured(): Boolean =
        instanceUrl.isNotBlank() &&
            when (auth.mode) {
                ServiceNowAuthMode.BASIC -> auth.username.isNotBlank() && auth.password.isNotBlank()
                ServiceNowAuthMode.OAUTH -> auth.clientId.isNotBlank() && auth.clientSecret.isNotBlank()
            }

    companion object {
        /** Services and applications: the CI classes that describe software rather than hardware. */
        val DEFAULT_CI_TABLES = listOf("cmdb_ci_service", "cmdb_ci_app", "cmdb_ci_business_app")
        const val DEFAULT_REPO_FIELD = "u_repository_url"

        /** ServiceNow names a relationship by both ends at once, parent-first. */
        val DEFAULT_REL_TYPES = listOf("Depends on::Used by")

        /** The Table API's practical maximum; more than this and it starts timing out. */
        const val DEFAULT_PAGE_SIZE = 500

        /** A quarter. Long enough to cover the changes anyone asks about, short enough to finish. */
        const val DEFAULT_LOOKBACK_DAYS = 90L
    }
}
