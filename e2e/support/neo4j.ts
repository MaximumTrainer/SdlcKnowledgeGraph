import { expect, request as apiRequest } from '@playwright/test'

/**
 * Neo4j's HTTP API on the compose stack, for the one fact a test cannot state through the
 * application's own API: an *inferred* edge. A write through `/api/v1/edges` is a principal stating
 * a fact, so its provenance is always `inferred: false` with confidence 1.0; only a connector's link
 * rule marks one inferred, and the stack runs none. The graph view draws inferred edges differently
 * (#9), so the spec states the edge through the API and then marks it the way a link rule would.
 *
 * The address and credentials are the development ones from compose.yaml, overridable for a stack
 * run elsewhere.
 */
const NEO4J_HTTP_URL = process.env.NEO4J_HTTP_URL ?? 'http://localhost:7474'
const NEO4J_USER = process.env.NEO4J_USERNAME ?? 'neo4j'
const NEO4J_PASSWORD = process.env.NEO4J_PASSWORD ?? 'password'

/** Runs one parameterised Cypher statement and hands back its rows, failing on any error. */
export async function cypher(
  statement: string,
  parameters: Record<string, unknown>
): Promise<unknown[][]> {
  const context = await apiRequest.newContext({
    httpCredentials: { username: NEO4J_USER, password: NEO4J_PASSWORD, send: 'always' }
  })
  try {
    const response = await context.post(`${NEO4J_HTTP_URL}/db/neo4j/tx/commit`, {
      data: { statements: [{ statement, parameters }] }
    })
    expect(response.ok(), `Neo4j answered ${response.status()}`).toBeTruthy()
    const body = (await response.json()) as {
      results: { data: { row: unknown[] }[] }[]
      errors: unknown[]
    }
    expect(body.errors, JSON.stringify(body.errors)).toEqual([])
    return body.results[0].data.map(entry => entry.row)
  } finally {
    await context.dispose()
  }
}

/** Marks one stored edge as a link rule would have written it: inferred, with [confidence]. */
export async function markInferred(
  type: string,
  fromId: string,
  toId: string,
  confidence: number
): Promise<void> {
  // The type is a label position, which Cypher cannot take as a parameter; it is checked first.
  expect(type).toMatch(/^[A-Z_]+$/)
  const rows = await cypher(
    `MATCH (a { id: $fromId })-[r:${type}]->(b { id: $toId })
     SET r.prov_inferred = true, r.prov_confidence = $confidence
     RETURN count(r)`,
    { fromId, toId, confidence }
  )
  expect(rows[0][0], `the ${type} edge from ${fromId} to ${toId}`).toBe(1)
}
