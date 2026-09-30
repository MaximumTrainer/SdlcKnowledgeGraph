package com.repodatagraph.acceptance.support

import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.support.connector.FakeConnector
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * A second scripted connector, `fake-itsm`, whose lifecycle rules differ from the fake's (#33): the
 * test profile gives it a grace period of seven days before a full sync retires what it stopped
 * reporting, as an ITSM connector's would, so a CMDB is not retired by one bad day at the source.
 *
 * A connector of its own rather than a setting on `fake`, because reconciliation (#150) is proved
 * against `fake` with no grace at all, and configuration is fixed for the life of the context. It
 * wraps a [FakeConnector] rather than being one, so the steps that inject the fake by type still find
 * exactly one.
 */
class FakeItsmConnector(
    val script: FakeConnector = FakeConnector(name = NAME),
) : SourceConnector by script {
    companion object {
        const val NAME = "fake-itsm"
    }
}

@TestConfiguration
class FakeItsmConnectorConfig {
    @Bean
    fun fakeItsmConnector(): FakeItsmConnector = FakeItsmConnector()
}
