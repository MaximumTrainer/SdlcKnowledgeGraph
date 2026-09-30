package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.config.AuthProperties
import com.repodatagraph.observability.LogEvents
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Makes the development bypass loud (#114, FR-5): every start with AUTH_DISABLED=true logs a
 * security-marked warning, so an instance running open is visible in its logs, not only in its
 * environment.
 */
@Component
class AuthDisabledWarning(
    private val auth: AuthProperties,
) {
    @EventListener(ApplicationStartedEvent::class)
    fun announce() {
        if (auth.disabled) LogEvents.authDisabled()
    }
}
