package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.AuthMode
import com.repodatagraph.config.AuthProperties
import com.repodatagraph.observability.LogEvents
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Names the anonymous read-only mode in the log (#118): every start with no identity provider logs a
 * security-marked warning, so an instance serving reads to anyone is visible in its logs, not only in
 * its environment. [AuthGuard][com.repodatagraph.config.AuthGuard] has already made sure it is read-only.
 */
@Component
class AnonymousReadOnlyWarning(
    private val auth: AuthProperties,
) {
    @EventListener(ApplicationStartedEvent::class)
    fun announce() {
        if (auth.mode == AuthMode.ANONYMOUS_READ_ONLY) LogEvents.authAnonymousReadonly()
    }
}
