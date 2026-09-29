package com.repodatagraph.domain.port.out

/**
 * How many nodes and relationships the graph holds, by type (#29, FR1).
 *
 * A port of its own rather than more methods on [GraphStore]: that one reads and writes facts one
 * key at a time, and this asks how many there are, which only the observability of the graph needs.
 * Both count every fact of the type, closed ones included, since the question is how big the store
 * is rather than what is currently true.
 */
interface GraphCensus {
    /** @throws com.repodatagraph.domain.exception.UnknownNodeTypeException if the type is not declared */
    fun countNodes(type: String): Long

    /** @throws com.repodatagraph.domain.exception.UnknownEdgeTypeException if the type is not declared */
    fun countEdges(type: String): Long
}
