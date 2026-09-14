package com.repodatagraph.support.connector

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Puts a [FakeConnector] in the context so the connector mechanism can be driven end to end without
 * a real system behind it.
 *
 * A singleton on purpose: the steps script it and then assert what it was asked, which only works if
 * the bean the application syncs is the same object the test holds.
 */
@TestConfiguration
class FakeConnectorConfig {
    @Bean
    fun fakeConnector(): FakeConnector = FakeConnector()
}
