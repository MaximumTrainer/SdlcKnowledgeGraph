package com.repodatagraph.support.connector

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Puts a [FakeServiceNow] in the context and points the ServiceNow connector at it.
 *
 * Started in a companion object rather than in the bean method, because the connector's instance URL
 * is a configuration property and configuration is bound before any bean exists. Whoever imports
 * this has to hand [baseUrl] to `connectors.servicenow.instance-url`.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeServiceNowConfig {
    @Bean
    fun fakeServiceNow(): FakeServiceNow = INSTANCE

    companion object {
        /**
         * One fake for the whole run, on a port the operating system picks.
         *
         * Its page size has to match `connectors.servicenow.page-size` in the test profile: the fake
         * serves the pages the connector asks for, and a mismatch would prove the wrong thing.
         */
        val INSTANCE: FakeServiceNow = FakeServiceNow(pageSize = PAGE_SIZE).apply { start() }

        const val PAGE_SIZE = 100

        val baseUrl: String get() = INSTANCE.baseUrl
    }
}
