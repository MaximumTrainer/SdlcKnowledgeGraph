package com.repodatagraph.config.links

import com.repodatagraph.application.connector.SyncRunCompleted
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.links.LinkResolutionEngine
import com.repodatagraph.application.links.LinkResolutionTrigger
import com.repodatagraph.application.links.LinkRule
import com.repodatagraph.application.links.LinkService
import com.repodatagraph.application.links.rules.DeploymentLinkRule
import com.repodatagraph.application.links.rules.IacLinkRule
import com.repodatagraph.application.links.rules.ManualLinkRule
import com.repodatagraph.application.links.rules.NamingLinkRule
import com.repodatagraph.application.links.rules.TagLinkRule
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.EnvironmentAliases
import com.repodatagraph.domain.port.`in`.LinkUseCase
import com.repodatagraph.domain.port.out.CurrentPrincipal
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.LinkQueries
import com.repodatagraph.observability.LogEvents
import com.repodatagraph.observability.SyncMetrics
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The link engine's parts (#28), built from `links.*`: its rules, in the plan's confidence table
 * unless configured otherwise; the engine; the use case, whose resolutions run one at a time on a
 * thread of their own; and what starts a resolution without a request - a connector's run, and the
 * nightly schedule.
 */
@Configuration
@EnableConfigurationProperties(LinksProperties::class)
class LinksConfig(
    private val properties: LinksProperties,
) {
    @Bean
    fun linkRules(): List<LinkRule> {
        val rules = properties.rules
        return listOfNotNull(
            ManualLinkRule(rules.manual.confidence ?: Provenance.FULL_CONFIDENCE).takeIf { rules.manual.enabled },
            TagLinkRule(rules.tag.confidence, rules.tag.keys).takeIf { rules.tag.enabled },
            DeploymentLinkRule(rules.deployment.confidence ?: DeploymentLinkRule.DEFAULT_CONFIDENCE).takeIf { rules.deployment.enabled },
            IacLinkRule(rules.iac.confidence ?: IacLinkRule.DEFAULT_CONFIDENCE).takeIf { rules.iac.enabled },
            NamingLinkRule(rules.naming.confidence ?: NamingLinkRule.DEFAULT_CONFIDENCE).takeIf { rules.naming.enabled },
        )
    }

    @Bean
    fun linkResolutionEngine(
        linkRules: List<LinkRule>,
        graphStore: GraphStore,
        clock: Clock,
    ): LinkResolutionEngine = LinkResolutionEngine(linkRules, properties.threshold, graphStore, clock)

    /** One thread: resolutions queue behind each other rather than interleave their writes. */
    @Bean(destroyMethod = "shutdown")
    fun linkResolutionExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "link-engine").apply { isDaemon = true } }

    @Suppress("LongParameterList")
    @Bean
    fun linkService(
        engine: LinkResolutionEngine,
        graphStore: GraphStore,
        queries: LinkQueries,
        runs: SyncRunRecorder,
        metrics: SyncMetrics,
        principal: CurrentPrincipal,
        environments: EnvironmentAliases,
        linkResolutionExecutor: ExecutorService,
        clock: Clock,
    ): LinkService = LinkService(engine, graphStore, queries, runs, metrics, principal, environments, linkResolutionExecutor, clock)

    @Bean
    fun linkResolutionTrigger(
        links: LinkUseCase,
        queries: LinkQueries,
    ): LinkResolutionTrigger = LinkResolutionTrigger(links, queries, properties.triggers.toSet())
}

/**
 * What starts a resolution without a request: a connector's finished run, and the nightly schedule.
 * Neither on a read-only instance, which nothing may write to (#48).
 */
@Component
class LinkResolutionStarter(
    private val links: LinkUseCase,
    private val trigger: LinkResolutionTrigger,
    private val properties: LinksProperties,
    private val taskScheduler: TaskScheduler,
    @Value("\${sdlc.read-only:false}") private val readOnly: Boolean,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun schedule() {
        val cron = properties.schedule
        if (readOnly || cron == CRON_DISABLED) return
        taskScheduler.schedule({ links.resolve(LinkScope()) }, CronTrigger(cron))
        LogEvents.linksScheduled(cron)
    }

    @EventListener
    fun onSyncRunCompleted(event: SyncRunCompleted) {
        if (readOnly) return
        try {
            trigger.onSyncRunCompleted(event)
        } catch (
            // The run that announced this is already recorded; a resolution that could not start is
            // the nightly pass's to catch up, not a reason to fail the connector.
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            LogEvents.connectorRunFailed("link-engine", event.runId, failure)
        }
    }

    private companion object {
        /** Spring's own spelling of "never", as `@Scheduled(cron = "-")` takes it. */
        const val CRON_DISABLED = "-"
    }
}
