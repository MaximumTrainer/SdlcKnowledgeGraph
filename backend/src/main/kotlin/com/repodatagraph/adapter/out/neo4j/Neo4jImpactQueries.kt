package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.DeploymentFacts
import com.repodatagraph.domain.model.DeploymentRecord
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerPath
import com.repodatagraph.domain.model.PathIndexEntry
import com.repodatagraph.domain.model.PathIndexKind
import com.repodatagraph.domain.model.PathSearch
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.Traversal
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The impact reads in Cypher (#21, FR8 and FR9), each one statement.
 *
 * Plain Cypher rather than APOC's path expander: the instances this runs against, the dogfood one
 * among them, have no plugins. A walk is a Neo4j 5 quantified path pattern whose relationship types
 * come from the [Traversal] the registry produced, and whose direction is checked per step - along
 * the stored direction for a forward edge, against it for an inverse one - so one pattern walks
 * edges in both directions without ever walking one the wrong way. Every label and relationship
 * type passes through [CypherBuilder] first, so only registry-declared names reach a query; the
 * depth is interpolated because a quantifier cannot be a parameter, and is an Int the caller bounded.
 *
 * Closed facts (a `prov_validTo`) are history and are not walked.
 */
@Repository
class Neo4jImpactQueries(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
) : ImpactQueryPort {
    override fun paths(
        root: NodeKey,
        traversal: Traversal,
        maxDepth: Int,
        limit: Int,
    ): PathSearch {
        if (traversal.edgeTypes.isEmpty()) return PathSearch(emptyList(), false)
        val label = cypher.nodeLabel(root.type)
        // One extra row says whether the limit cut the answer short.
        val rows =
            neo4jClient
                .query(
                    """
                    MATCH (root:$label { key: ${'$'}key })
                    MATCH p = (root) ${step(traversal)}{1,${depth(maxDepth)}} (n)
                    WHERE n <> root
                    RETURN labels(n)[0] AS type, n { .* } AS node, $PATH_COLUMNS
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                ).bindAll(parameters(traversal) + ("key" to root.key) + ("limit" to limit.toLong() + 1))
                .fetch()
                .all()
                .toList()

        val paths =
            rows.take(limit).map { row ->
                val target = GraphRowMapper.toNode(row["type"].toString(), row["node"])
                CandidatePath(target, ImpactRowMapper.steps(row, traversal))
            }
        return PathSearch(paths, truncated = rows.size > limit)
    }

    override fun ownerPaths(
        node: NodeKey,
        inheritance: Traversal,
        ownerEdges: Set<String>,
        maxDepth: Int,
    ): List<OwnerPath> {
        if (ownerEdges.isEmpty()) return emptyList()
        val label = cypher.nodeLabel(node.type)
        val owner = ownerEdges.joinToString("|") { cypher.edgeType(it) }
        // Zero steps of inheritance is the node's own owner; a quantifier needs a pattern to repeat, so
        // with nothing to inherit along the owner edge is matched from the node itself.
        val pattern =
            if (inheritance.edgeTypes.isEmpty()) {
                "(start)-[o:$owner]->(team)"
            } else {
                "(start) ${step(inheritance)}{0,${depth(maxDepth)}} (owned)-[o:$owner]->(team)"
            }

        return neo4jClient
            .query(
                """
                MATCH (start:$label { key: ${'$'}key })
                MATCH p = $pattern
                WHERE o.prov_validTo IS NULL AND team.prov_validTo IS NULL
                RETURN labels(team)[0] AS type, team { .* } AS team, $PATH_COLUMNS
                """.trimIndent(),
            ).bindAll(parameters(inheritance) + ("key" to node.key))
            .fetch()
            .all()
            // The owner edge is walked as stored, so the traversal names it by its own name.
            .map { row -> OwnerPath(GraphRowMapper.toNode(row["type"].toString(), row["team"]), ImpactRowMapper.steps(row, inheritance)) }
    }

    /**
     * One statement per node type among [nodes], since a label cannot be a parameter; each walks
     * the same pattern as [ownerPaths] from every node of that type at once.
     */
    override fun ownerPathsOf(
        nodes: Collection<NodeKey>,
        inheritance: Traversal,
        ownerEdges: Set<String>,
        maxDepth: Int,
    ): Map<NodeKey, List<OwnerPath>> {
        if (ownerEdges.isEmpty() || nodes.isEmpty()) return emptyMap()
        val owner = ownerEdges.joinToString("|") { cypher.edgeType(it) }
        val pattern =
            if (inheritance.edgeTypes.isEmpty()) {
                "(start)-[o:$owner]->(team)"
            } else {
                "(start) ${step(inheritance)}{0,${depth(maxDepth)}} (owned)-[o:$owner]->(team)"
            }
        return nodes
            .distinct()
            .groupBy { it.type }
            .flatMap { (type, keys) ->
                neo4jClient
                    .query(
                        """
                        UNWIND ${'$'}keys AS key
                        MATCH (start:${cypher.nodeLabel(type)} { key: key })
                        MATCH p = $pattern
                        WHERE o.prov_validTo IS NULL AND team.prov_validTo IS NULL
                        RETURN start.key AS start, labels(team)[0] AS type, team { .* } AS team, $PATH_COLUMNS
                        """.trimIndent(),
                    ).bindAll(parameters(inheritance) + ("keys" to keys.map { it.key }))
                    .fetch()
                    .all()
                    .map { row ->
                        NodeKey(type, row["start"].toString()) to
                            OwnerPath(GraphRowMapper.toNode(row["type"].toString(), row["team"]), ImpactRowMapper.steps(row, inheritance))
                    }
            }.groupBy({ it.first }, { it.second })
    }

    override fun placements(
        nodes: Collection<NodeKey>,
        edges: Set<String>,
    ): Map<NodeKey, List<GraphNode>> {
        if (edges.isEmpty() || nodes.isEmpty()) return emptyMap()
        val types = edges.joinToString("|") { cypher.edgeType(it) }
        return nodes
            .distinct()
            .groupBy { it.type }
            .flatMap { (type, keys) ->
                neo4jClient
                    .query(
                        """
                        UNWIND ${'$'}keys AS key
                        MATCH (n:${cypher.nodeLabel(type)} { key: key })-[r:$types]->(t)
                        WHERE r.prov_validTo IS NULL AND t.prov_validTo IS NULL
                        RETURN n.key AS start, labels(t)[0] AS type, t { .* } AS node
                        """.trimIndent(),
                    ).bindAll(mapOf("keys" to keys.map { it.key }))
                    .fetch()
                    .all()
                    .map { row -> NodeKey(type, row["start"].toString()) to GraphRowMapper.toNode(row["type"].toString(), row["node"]) }
            }.groupBy({ it.first }, { it.second })
    }

    /**
     * The index the GitHub connector writes (#23): an IacFile per infrastructure file the repository
     * CONTAINS_IAC, and the `manifest` each DEPENDS_ON edge was read from. The edge names are that
     * connector's, each checked against the registry.
     */
    override fun pathIndex(repository: NodeKey): List<PathIndexEntry> {
        val label = cypher.nodeLabel(repository.type)
        val containsIac = cypher.edgeType("CONTAINS_IAC")
        val entry = { row: Map<String, Any?>, kind: PathIndexKind ->
            PathIndexEntry(
                path = row["path"].toString(),
                kind = kind,
                names =
                    (row["names"] as? List<*>)
                        .orEmpty()
                        .filterNotNull()
                        .map { it.toString() }
                        .sorted(),
            )
        }
        val dependsOn = cypher.edgeType("DEPENDS_ON")
        val iac =
            neo4jClient
                .query(
                    """
                    MATCH (:$label { key: ${'$'}key })-[c:$containsIac]->(f)
                    WHERE c.prov_validTo IS NULL AND f.prov_validTo IS NULL AND f.path IS NOT NULL
                    RETURN f.path AS path, coalesce(f.resourceRefs, []) AS names
                    """.trimIndent(),
                ).bindAll(mapOf("key" to repository.key))
                .fetch()
                .all()
                .map { entry(it, PathIndexKind.IAC) }
        val manifests =
            neo4jClient
                .query(
                    """
                    MATCH (:$label { key: ${'$'}key })-[d:$dependsOn]->(x)
                    WHERE d.prov_validTo IS NULL AND d.manifest IS NOT NULL
                    RETURN d.manifest AS path, collect(x.key) AS names
                    """.trimIndent(),
                ).bindAll(mapOf("key" to repository.key))
                .fetch()
                .all()
                .map { entry(it, PathIndexKind.MANIFEST) }
        return iac + manifests
    }

    override fun deploymentFacts(
        deployment: NodeKey,
        dependencyDepth: Int,
    ): DeploymentFacts? {
        val row =
            neo4jClient
                .query(deploymentFactsQuery(dependencyDepth))
                .bindAll(mapOf("key" to deployment.key))
                .fetch()
                .one()
                .orElse(null) ?: return null

        val node = GraphRowMapper.toNode("Deployment", row["deployment"])
        return DeploymentFacts(
            deployment = node,
            status = node.props["status"]?.toString() ?: ImpactRowMapper.UNKNOWN_STATUS,
            deployedAt = ProvenanceMapper.instant(node.props["deployedAt"]) ?: Instant.EPOCH,
            artifact = ImpactRowMapper.optionalNode("Artifact", row["artifact"]),
            commitSha = row["commitSha"]?.toString(),
            repository = ImpactRowMapper.optionalNode("Repository", row["repository"]),
            pipeline = ImpactRowMapper.optionalNode("Pipeline", row["pipeline"]),
            environment = ImpactRowMapper.optionalNode("Environment", row["environment"]),
            history = ImpactRowMapper.records(row["history"], "Repository"),
            dependencyDeployments = ImpactRowMapper.records(row["dependencies"], "Repository"),
        )
    }

    /**
     * The lineage of one deployment and the deployments around it, in one statement: the history of
     * its repository in its environment, and its dependencies' deployments there. The edge names are
     * the lineage the deployment ingest writes (#7); each is checked against the registry, so a
     * renamed edge fails loudly rather than finding nothing.
     */
    private fun deploymentFactsQuery(dependencyDepth: Int): String {
        val deployedTo = cypher.edgeType("DEPLOYED_TO")
        val builtFrom = cypher.edgeType("BUILT_FROM")
        val toEnvironment = cypher.edgeType("TO_ENVIRONMENT")
        val hasPipeline = cypher.edgeType("HAS_PIPELINE")
        val dependsOn = cypher.edgeType("DEPENDS_ON")
        val deploymentLabel = cypher.nodeLabel("Deployment")
        val artifactLabel = cypher.nodeLabel("Artifact")
        val repositoryLabel = cypher.nodeLabel("Repository")
        val environmentLabel = cypher.nodeLabel("Environment")

        return """
            MATCH (d:$deploymentLabel { key: ${'$'}key })
            OPTIONAL MATCH (a:$artifactLabel)-[:$deployedTo]->(d)
            OPTIONAL MATCH (a)-[bf:$builtFrom]->(r:$repositoryLabel)
            OPTIONAL MATCH (d)-[:$toEnvironment]->(e:$environmentLabel)
            CALL (r) {
              OPTIONAL MATCH (r)-[:$hasPipeline]->(pl)
              WITH pl ORDER BY pl.key
              RETURN head(collect(pl { .* })) AS pipeline
            }
            CALL (r, e) {
              MATCH (r)<-[b2:$builtFrom]-(a2:$artifactLabel)-[:$deployedTo]->(d2:$deploymentLabel)-[:$toEnvironment]->(e)
              RETURN collect(DISTINCT {
                id: d2.id, repository: r.key, commitSha: coalesce(b2.commitSha, a2.commitSha),
                deployedAt: d2.deployedAt, status: d2.status
              }) AS history
            }
            CALL (r, e) {
              MATCH (r)-[:$dependsOn*1..${depth(dependencyDepth)}]->(dep:$repositoryLabel)
                    <-[b3:$builtFrom]-(a3:$artifactLabel)-[:$deployedTo]->(d3:$deploymentLabel)-[:$toEnvironment]->(e)
              WHERE dep <> r
              RETURN collect(DISTINCT {
                id: d3.id, repository: dep.key, commitSha: coalesce(b3.commitSha, a3.commitSha),
                deployedAt: d3.deployedAt, status: d3.status
              }) AS dependencies
            }
            RETURN d { .* } AS deployment, a { .* } AS artifact, coalesce(bf.commitSha, a.commitSha) AS commitSha,
                   r { .* } AS repository, e { .* } AS environment, pipeline, history, dependencies
            LIMIT 1
            """.trimIndent()
    }

    /**
     * One step of a walk: an edge of one of the traversal's types, taken along its stored direction
     * when the traversal lists it as forward and against it when it lists it as inverse.
     */
    private fun step(traversal: Traversal): String {
        val types = traversal.edgeTypes.joinToString("|") { cypher.edgeType(it) }
        return """
            ((a)-[r:$types]-(b)
              WHERE ((type(r) IN ${'$'}forward AND startNode(r) = a) OR (type(r) IN ${'$'}inverse AND endNode(r) = a))
                AND r.prov_validTo IS NULL AND b.prov_validTo IS NULL)
            """.trimIndent()
    }

    private fun parameters(traversal: Traversal): Map<String, Any> =
        mapOf(
            "forward" to traversal.forward.toList(),
            "inverse" to traversal.inverse.keys.toList(),
        )

    /** The bound a caller set, refused here too, because it is written into the query rather than bound. */
    private fun depth(value: Int): Int {
        require(value in 1..MAX_DEPTH) { "depth $value is outside 1..$MAX_DEPTH" }
        return value
    }

    private companion object {
        const val MAX_DEPTH = 10

        /** Each path as the ids of its nodes in walk order, and its relationships with the facts a step needs. */
        const val PATH_COLUMNS =
            "[x IN nodes(p) | x.id] AS ids, " +
                "[x IN relationships(p) | {type: type(x), from: startNode(x).id, " +
                "confidence: x.prov_confidence, inferred: x.prov_inferred}] AS rels"
    }
}

/** Reads the rows the impact queries return into the domain's paths and records. */
internal object ImpactRowMapper {
    const val UNKNOWN_STATUS = "UNKNOWN"

    fun steps(
        row: Map<String, Any?>,
        traversal: Traversal,
    ): List<PathStep> {
        val ids = (row["ids"] as List<*>).map { it.toString() }
        return relationships(row).mapIndexed { index, relationship ->
            val from = ids[index]
            val type = relationship["type"].toString()
            PathStep(
                edge = traversal.nameOf(type, alongStoredDirection = relationship["from"] == from),
                from = from,
                to = ids[index + 1],
                // A relationship written before provenance was recorded on it is taken as stated.
                confidence = (relationship["confidence"] as? Number)?.toDouble() ?: Provenance.FULL_CONFIDENCE,
                inferred = relationship["inferred"] as? Boolean ?: false,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun relationships(row: Map<String, Any?>): List<Map<String, Any?>> = row["rels"] as List<Map<String, Any?>>

    fun optionalNode(
        type: String,
        value: Any?,
    ): GraphNode? = value?.let { GraphRowMapper.toNode(type, it) }

    fun records(
        value: Any?,
        repositoryType: String,
    ): List<DeploymentRecord> =
        (value as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { record ->
            DeploymentRecord(
                id = record["id"].toString(),
                repository = record["repository"]?.let { NodeKey(repositoryType, it.toString()) },
                commitSha = record["commitSha"]?.toString(),
                deployedAt = ProvenanceMapper.instant(record["deployedAt"]) ?: Instant.EPOCH,
                status = record["status"]?.toString() ?: UNKNOWN_STATUS,
            )
        }
}
