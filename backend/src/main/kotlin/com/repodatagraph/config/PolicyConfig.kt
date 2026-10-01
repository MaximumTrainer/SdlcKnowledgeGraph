package com.repodatagraph.config

import com.repodatagraph.adapter.out.policy.PolicyBundle
import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * The policy decision point (#30 FR1, ADR-0020): the bundle the API was built with, or the one
 * SDLC_POLICY_BUNDLE names. A bundle that cannot be read or compiled stops the application at
 * startup, so an instance never runs without the policy it was configured with.
 */
@Configuration
@EnableConfigurationProperties(PolicyProperties::class)
class PolicyConfig {
    // No destroy method: the default instance is shared by every application context in the JVM, so
    // closing it with one context would break the next.
    @Bean(destroyMethod = "")
    fun policyDecisionPoint(properties: PolicyProperties): PolicyDecisionPoint =
        properties.bundle
            ?.takeIf { it.isNotBlank() }
            ?.let { WasmPolicyDecisionPoint(PolicyBundle.fromFile(Path.of(it))) }
            ?: WasmPolicyDecisionPoint.classpathDefault()
}
