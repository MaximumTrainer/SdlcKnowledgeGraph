package com.repodatagraph.domain.model

/**
 * The address of a node: its type plus a key derived from its own properties.
 *
 * Keys are derived, never generated, so that the same real-world thing reported by two different
 * connectors lands on one node instead of two.
 */
data class NodeKey(
    val type: String,
    val key: String,
) {
    init {
        require(type.isNotBlank()) { "node type must not be blank" }
        require(key.isNotBlank()) { "node key must not be blank" }
    }

    /** Stable public identifier, for example `Repository:github.com/acme/payments`. */
    val id: String get() = "$type$SEPARATOR$key"

    override fun toString(): String = id

    companion object {
        private const val SEPARATOR = ":"

        /**
         * Parses an id of the form `Type:key`. The key itself may contain separators, as cloud
         * resource keys do, so only the first one is significant.
         */
        fun parse(id: String): NodeKey {
            val index = id.indexOf(SEPARATOR)
            require(index > 0 && index < id.length - 1) { "'$id' is not a node id of the form Type:key" }
            return NodeKey(id.substring(0, index), id.substring(index + 1))
        }
    }
}
