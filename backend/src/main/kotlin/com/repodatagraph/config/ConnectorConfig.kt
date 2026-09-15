package com.repodatagraph.config

import com.repodatagraph.adapter.out.github.GitHubProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock

/**
 * What the connector mechanism needs from the framework.
 *
 * The [Clock] is a bean rather than a call to `Instant.now()` so that a test can say what time it is.
 * Provenance and watermarks are both times, and asserting on them is impossible when the code reads
 * the wall clock directly.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ConnectorsProperties::class, GitHubProperties::class)
class ConnectorConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Syncs run here, off the request thread.
     *
     * A small pool on purpose: connectors read somebody else's API under somebody else's rate limit,
     * so the useful concurrency is a few, and an unbounded pool would turn a burst of manual syncs
     * into a burst of 429s.
     */
    @Bean
    fun connectorTaskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = POOL_SIZE
            setThreadNamePrefix("connector-sync-")
            // A sync that is mid-page when the application stops should finish writing it, so the
            // graph is not left with half a page and a run that says RUNNING for ever.
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(SHUTDOWN_GRACE_SECONDS)
            initialize()
        }

    private companion object {
        const val POOL_SIZE = 4
        const val SHUTDOWN_GRACE_SECONDS = 30
    }
}
