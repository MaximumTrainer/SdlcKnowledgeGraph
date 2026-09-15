package com.repodatagraph.support.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import com.github.tomakehurst.wiremock.verification.LoggedRequest

/**
 * A ServiceNow that is not ServiceNow, over real HTTP.
 *
 * The Table API is one endpoint with everything expressed in query parameters, so a fake serving
 * fixed bodies would prove nothing. What is worth testing is that the connector asks the right
 * question - the right window, the right page - and that is only visible in the request it made, so
 * rows are held here and served in pages the way the real API serves them.
 *
 * @param pageSize must match `connectors.servicenow.page-size`, because that is what the connector
 *   asks for and a fake that paged differently would prove the wrong thing
 */
class FakeServiceNow(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val server = WireMockServer(options().dynamicPort())
    private val rows = mutableMapOf<String, MutableList<String>>()

    val baseUrl: String get() = server.baseUrl()

    fun start() {
        server.start()
        stubEverything()
    }

    fun stop() = server.stop()

    fun reset() {
        server.resetAll()
        rows.clear()
        stubEverything()
    }

    /**
     * Every table the connector might ask for, empty until something is added.
     *
     * The real Table API answers an empty result for a table with no matching rows; only a table that
     * does not exist is a 404. A fake that 404'd an empty table would make "nothing to sync" look
     * like a misconfigured instance.
     */
    private fun stubEverything() = TABLES.forEach { stubTable(it) }

    /** Every request the connector made, for asserting on the query it asked rather than the answer. */
    fun requests(): List<LoggedRequest> = server.allServeEvents.map { it.request }

    /** Forgets what has been asked so far, keeping the rows. For "and now ask again" scenarios. */
    fun forgetRequests() = server.resetRequests()

    /**
     * Adds rows to a table, as JSON objects.
     *
     * Given as JSON rather than as a typed row because the Table API's shapes differ per field -
     * `sysparm_display_value=all` wraps some fields in `{display_value, value}` and leaves others
     * plain - and a typed fake would quietly normalise away the thing the mapper has to cope with.
     */
    fun has(
        table: String,
        json: List<String>,
    ) {
        rows.getOrPut(table) { mutableListOf() } += json
        stubTable(table)
    }

    /** Serves a table in pages, so a connector that ignores paging reads a fraction of the rows. */
    private fun stubTable(table: String) {
        val all = rows[table].orEmpty()
        // Any offset past the end: an empty result, which is how the real API says "that is all".
        server.stubFor(
            get(urlPathEqualTo(tablePath(table)))
                .willReturn(jsonResponse("""{"result":[]}""")),
        )
        var offset = 0
        while (offset == 0 || offset < all.size) {
            val page = all.drop(offset).take(pageSize)
            server.stubFor(
                get(urlPathEqualTo(tablePath(table)))
                    .withQueryParam(OFFSET, EqualToPattern(offset.toString()))
                    .willReturn(jsonResponse(page.joinToString(",", """{"result":[""", "]}"))),
            )
            offset += pageSize
        }
    }

    private fun tablePath(table: String) = "/api/now/table/$table"

    private fun jsonResponse(body: String) =
        aResponse()
            .withStatus(OK)
            .withHeader("Content-Type", "application/json")
            .withBody(body)

    companion object {
        /** The tables this connector reads, plus the one its health check uses. */
        val TABLES =
            listOf(
                "cmdb_ci_service",
                "cmdb_ci_app",
                "cmdb_ci_business_app",
                "cmdb_rel_ci",
                "change_request",
                "incident",
                "sys_properties",
            )

        const val DEFAULT_PAGE_SIZE = 500
        private const val OFFSET = "sysparm_offset"
        private const val OK = 200
    }
}

/**
 * A CMDB row, in the shape `sysparm_display_value=all` returns.
 *
 * Reference fields come back as `{"display_value": ..., "value": ...}` and plain fields as a string.
 * The distinction matters: a connector reading `value` of a reference gets the sys_id it needs to
 * build an edge, and one reading `display_value` gets a human label that points at nothing.
 */
object ServiceNowRows {
    fun ci(
        sysId: String,
        name: String,
        ciClass: String,
        updated: String = "2026-09-01 10:00:00",
        status: String = "Operational",
        repositoryUrl: String? = null,
        supportGroup: String? = null,
    ): String =
        """
        {
          "sys_id": ${plain(sysId)},
          "name": ${plain(name)},
          "sys_class_name": ${reference(ciClass, ciClass)},
          "sys_updated_on": ${plain(updated)},
          "operational_status": ${reference(status, "1")},
          "support_group": ${reference(supportGroup ?: "", supportGroup ?: "")},
          "u_repository_url": ${plain(repositoryUrl ?: "")}
        }
        """.trimIndent()

    fun relationship(
        sysId: String,
        parent: String,
        child: String,
        type: String,
        updated: String = "2026-09-01 10:00:00",
    ): String =
        """
        {
          "sys_id": ${plain(sysId)},
          "parent": ${reference("parent-ci", parent)},
          "child": ${reference("child-ci", child)},
          "type": ${reference(type, "type-$sysId")},
          "sys_updated_on": ${plain(updated)}
        }
        """.trimIndent()

    fun change(
        sysId: String,
        number: String,
        affects: String,
        updated: String = "2026-09-01 10:00:00",
        state: String = "Closed",
        type: String = "normal",
    ): String =
        """
        {
          "sys_id": ${plain(sysId)},
          "number": ${plain(number)},
          "short_description": ${plain("Deploy $number")},
          "state": ${reference(state, "3")},
          "type": ${reference(type, type)},
          "risk": ${reference("Moderate", "3")},
          "cmdb_ci": ${reference("affected-ci", affects)},
          "sys_updated_on": ${plain(updated)}
        }
        """.trimIndent()

    fun incident(
        sysId: String,
        number: String,
        affects: String,
        causedBy: String? = null,
        updated: String = "2026-09-01 10:00:00",
    ): String =
        """
        {
          "sys_id": ${plain(sysId)},
          "number": ${plain(number)},
          "short_description": ${plain("Outage $number")},
          "state": ${reference("Resolved", "6")},
          "priority": ${reference("1 - Critical", "1")},
          "cmdb_ci": ${reference("affected-ci", affects)},
          "caused_by": ${reference(causedBy ?: "", causedBy ?: "")},
          "sys_updated_on": ${plain(updated)}
        }
        """.trimIndent()

    private fun plain(value: String) = "\"$value\""

    private fun reference(
        display: String,
        value: String,
    ) = """{"display_value":"$display","value":"$value"}"""
}
