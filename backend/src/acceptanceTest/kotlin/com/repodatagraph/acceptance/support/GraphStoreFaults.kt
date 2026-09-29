package com.repodatagraph.acceptance.support

import com.repodatagraph.adapter.out.neo4j.Neo4jGraphStore
import com.repodatagraph.domain.port.out.GraphStore
import org.neo4j.driver.exceptions.ServiceUnavailableException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Makes the real graph store fail on demand, so a scenario can see what the application does when
 * Neo4j does: the store is wrapped where it is created, beneath anything the application puts around
 * it, and fails the next call to the operation a scenario names, once, the way a dropped connection
 * would.
 */
@TestConfiguration(proxyBeanMethods = false)
class GraphStoreFaults {
    @Bean
    fun failingGraphStore(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any = if (bean is Neo4jGraphStore) failing(bean) else bean
        }

    // Reflection takes the arguments as varargs; the copy is of a handful of references.
    @Suppress("SpreadOperator")
    private fun failing(store: GraphStore): GraphStore {
        val handler =
            InvocationHandler { _, method, args ->
                if (next != null && method.name == next) {
                    next = null
                    throw ServiceUnavailableException("injected: the graph store is unavailable")
                }
                try {
                    method.invoke(store, *args.orEmpty())
                } catch (thrown: InvocationTargetException) {
                    throw thrown.targetException
                }
            }
        return Proxy.newProxyInstance(GraphStore::class.java.classLoader, arrayOf(GraphStore::class.java), handler) as GraphStore
    }

    companion object {
        /** The operation whose next call fails; cleared once it has. */
        @Volatile
        var next: String? = null
    }
}
