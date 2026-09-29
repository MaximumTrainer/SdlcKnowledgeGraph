#!/usr/bin/env node
/**
 * Seeds this repository's own SDLC into the dogfood instance (#47, FR10): the repository, a team per
 * CODEOWNERS owner, a pipeline per workflow with its last run's status, and the repositories its
 * manifests depend on by git remote. It posts one batch to POST /api/v1/ingest/seed
 * (docs/ADAPTERS.md, "Seeding"), and is replaced by the GitHub connector (#23).
 *
 *   GITHUB_REPOSITORY=MaximumTrainer/SdlcKnowledgeGraph GITHUB_TOKEN=... \
 *   SEED_BASE_URL=https://sdlc-graph.fly.dev INGEST_TOKEN=... node scripts/dogfood-seed.mjs
 *
 * Optional: SEED_NODE_CEILING (default 1000), GITHUB_API_URL (default https://api.github.com).
 *
 * It fails rather than skipping when the instance cannot be reached, and when the instance holds more
 * nodes than the ceiling, which is the cheap sign that something is writing to it that should not be
 * (FR11). Seeding again is safe: identity is derived, so a rerun changes properties, never counts.
 */
import { fileURLToPath } from 'node:url'

/** The manifests FR10 names. A dependency counts only when it resolves to a git remote. */
const MANIFESTS = [
  { path: 'frontend/package.json', read: (path, text) => dependenciesFromPackageJson(path, text) },
  { path: 'backend/build.gradle.kts', read: (path, text) => dependenciesFromGradle(path, text) },
]
const CODEOWNERS_PATHS = ['.github/CODEOWNERS', 'CODEOWNERS', 'docs/CODEOWNERS']
const WORKFLOW_DIRECTORY = '.github/workflows/'
const DEFAULT_CEILING = 1000
const PAGE = 500

/** `* @a @org/team  # comment` as `{ pattern, owners }`; a rule naming nobody is skipped. */
export const parseCodeowners = text =>
  (text ?? '')
    .split('\n')
    .map(line => line.replace(/#.*/, '').trim())
    .filter(Boolean)
    .map(line => {
      const [pattern, ...owners] = line.split(/\s+/)
      return { pattern, owners }
    })
    .filter(rule => rule.owners.length > 0)

const GIT_URL = /^(?:git\+)?(?:https?|ssh|git):\/\/(?:[^@/]+@)?([^/:]+)[/:]([^/]+)\/([^/#]+?)(?:\.git)?(?:#(.*))?$/
const HOSTED = /^(github|gitlab|bitbucket):([^/]+)\/([^/#]+?)(?:#(.*))?$/
const SHORTHAND = /^([A-Za-z0-9][\w.-]*)\/([\w.-]+?)(?:#(.*))?$/
const HOSTS = { github: 'github.com', gitlab: 'gitlab.com', bitbucket: 'bitbucket.org' }

/** `host/org/name`, lowercased as the server keys a repository, or null for a registry version. */
const remoteOf = spec => {
  const url = spec.match(GIT_URL)
  if (url) return { remote: `${url[1]}/${url[2]}/${url[3]}`.toLowerCase(), ref: url[4] }
  const hosted = spec.match(HOSTED)
  if (hosted) return { remote: `${HOSTS[hosted[1]]}/${hosted[2]}/${hosted[3]}`.toLowerCase(), ref: hosted[4] }
  const shorthand = spec.match(SHORTHAND)
  if (shorthand) return { remote: `github.com/${shorthand[1]}/${shorthand[2]}`.toLowerCase(), ref: shorthand[3] }
  return null
}

/** The dependencies of a package.json that are git remotes, not registry versions or local paths. */
export const dependenciesFromPackageJson = (manifest, text) => {
  if (!text) return []
  const parsed = JSON.parse(text)
  return ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies']
    .flatMap(section => Object.values(parsed[section] ?? {}))
    .flatMap(spec => {
      const resolved = remoteOf(spec)
      return resolved ? [{ remote: resolved.remote, manifest, version: resolved.ref ?? spec }] : []
    })
}

/** Gradle names a git remote only for a source dependency: `gitRepository(uri("..."))`. */
export const dependenciesFromGradle = (manifest, text) =>
  [...(text ?? '').matchAll(/gitRepository\(\s*(?:uri|java\.net\.URI)\(\s*"([^"]+)"\s*\)/g)].flatMap(([, url]) => {
    const resolved = remoteOf(url)
    return resolved ? [{ remote: resolved.remote, manifest, version: resolved.ref }] : []
  })

const repositoryNode = repository => {
  const url = `https://github.com/${repository.fullName}`
  const props = {
    url,
    defaultBranch: repository.defaultBranch,
    topics: repository.topics ?? [],
    codeowners: repository.codeowners ?? [],
    description: repository.description ?? undefined,
    language: repository.language ?? undefined,
    visibility: repository.visibility ?? undefined,
  }
  return { type: 'Repository', sourceId: url, props: withoutUndefined(props) }
}

const withoutUndefined = props => Object.fromEntries(Object.entries(props).filter(([, value]) => value != null))

/**
 * The batch for one repository. Nodes and edges come out in the order they went in, so the same
 * reading builds the same bytes, and the instance treats an unchanged rerun as one delivery.
 */
export const buildSeed = ({ repository, codeowners, workflows, dependencies }) => {
  const owners = [...new Set(codeowners.flatMap(rule => rule.owners))]
  const repoKey = `github.com/${repository.fullName}`.toLowerCase()
  const nodes = [repositoryNode({ ...repository, codeowners: owners })]
  const edges = []
  const add = node => nodes.push(node) - 1

  owners.forEach(owner => {
    const team = add({ type: 'Team', props: { name: owner.replace(/^@/, '').toLowerCase() } })
    const pathPatterns = codeowners.filter(rule => rule.owners.includes(owner)).map(rule => rule.pattern)
    edges.push({ type: 'OWNED_BY', from: 0, to: team, props: { pathPatterns } })
  })

  workflows.forEach(workflow => {
    const pipeline = add({
      type: 'Pipeline',
      props: withoutUndefined({
        provider: 'github-actions',
        repoKey,
        workflowPath: workflow.path,
        name: workflow.name,
        repoId: repoKey,
        lastRunStatus: workflow.lastRunStatus,
      }),
    })
    edges.push({ type: 'HAS_PIPELINE', from: 0, to: pipeline, props: {} })
  })

  dependencies.forEach(dependency => {
    const target = add(repositoryNode(dependency.repository))
    const props = withoutUndefined({ kind: 'library', manifest: dependency.manifest, version: dependency.version })
    edges.push({ type: 'DEPENDS_ON', from: 0, to: target, props })
  })

  return { nodes, edges }
}

/** What GitHub says about one repository, in the shape the batch needs, or null if it is not there. */
const readRepository = async (github, fullName) => {
  const repository = await github(`/repos/${fullName}`)
  if (!repository) return null
  return {
    fullName: repository.full_name,
    defaultBranch: repository.default_branch,
    topics: repository.topics ?? [],
    description: repository.description,
    language: repository.language,
    visibility: repository.visibility,
  }
}

const readFile = async (github, fullName, path, ref) => {
  const file = await github(`/repos/${fullName}/contents/${encodeURIComponent(path)}?ref=${encodeURIComponent(ref)}`)
  return file?.content ? Buffer.from(file.content, file.encoding ?? 'base64').toString('utf8') : null
}

const readCodeowners = async (github, fullName, ref) => {
  for (const path of CODEOWNERS_PATHS) {
    const text = await readFile(github, fullName, path, ref)
    if (text != null) return parseCodeowners(text)
  }
  return []
}

/** Workflow files only: GitHub also lists workflows it generates, such as Pages, which are not files here. */
const readWorkflows = async (github, fullName, branch) => {
  const { workflows } = await github(`/repos/${fullName}/actions/workflows?per_page=100`)
  const files = workflows.filter(workflow => workflow.path.startsWith(WORKFLOW_DIRECTORY))
  return Promise.all(
    files.map(async workflow => {
      const runs = await github(`/repos/${fullName}/actions/workflows/${workflow.id}/runs?branch=${encodeURIComponent(branch)}&per_page=1`)
      const latest = runs?.workflow_runs?.[0]
      return { path: workflow.path, name: workflow.name, lastRunStatus: latest ? (latest.conclusion ?? latest.status) : undefined }
    }),
  )
}

/** A dependency is seeded only when GitHub can say what its default branch is; the ontology needs it. */
const readDependencies = async (github, fullName, ref) => {
  const found = []
  for (const manifest of MANIFESTS) {
    found.push(...manifest.read(manifest.path, await readFile(github, fullName, manifest.path, ref)))
  }
  const resolved = await Promise.all(
    found
      .filter(dependency => dependency.remote.startsWith('github.com/'))
      .map(async dependency => ({ ...dependency, repository: await readRepository(github, dependency.remote.slice('github.com/'.length)) })),
  )
  return resolved.filter(dependency => dependency.repository)
}

const countNodes = async instance => {
  const ontology = await instance('GET', '/api/v1/ontology')
  let total = 0
  for (const type of ontology.body.nodeTypes.filter(nodeType => !nodeType.meta)) {
    let cursor = null
    do {
      const query = cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
      const page = await instance('GET', `/api/v1/nodes/${type.name}?limit=${PAGE}${query}`)
      total += page.body.items.length
      cursor = page.body.nextCursor
    } while (cursor)
  }
  return total
}

/**
 * Reads [repository] through [github] and seeds it into [instance].
 *
 * @param github `path => json`, null for a 404
 * @param instance `(method, path, body) => { status, body }`
 */
export const seed = async ({ repository: fullName, github, instance, ceiling, log = console.log }) => {
  try {
    await instance('GET', '/actuator/health')
  } catch (error) {
    throw new Error(`cannot reach the instance: ${error.message}`)
  }

  const repository = await readRepository(github, fullName)
  if (!repository) throw new Error(`GitHub has no repository ${fullName}`)
  const batch = buildSeed({
    repository,
    codeowners: await readCodeowners(github, fullName, repository.defaultBranch),
    workflows: await readWorkflows(github, fullName, repository.defaultBranch),
    dependencies: await readDependencies(github, fullName, repository.defaultBranch),
  })

  const answer = await instance('POST', '/api/v1/ingest/seed', batch)
  if (answer.status !== 202) {
    throw new Error(`the seed was refused (${answer.status}): ${JSON.stringify(answer.body)}`)
  }
  log(`seeded ${answer.body.nodes} nodes and ${answer.body.edges} edges (created: ${answer.body.created})`)

  const total = await countNodes(instance)
  if (total > ceiling) {
    throw new Error(`the instance holds ${total} nodes, more than the ceiling of ${ceiling}: something else is writing to it`)
  }
  log(`the instance holds ${total} nodes, within the ceiling of ${ceiling}`)
}

const gitHubClient =
  ({ api, token }) =>
  async path => {
    const response = await fetch(`${api}${path}`, {
      headers: { Accept: 'application/vnd.github+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
    if (response.status === 404) return null
    if (!response.ok) throw new Error(`GitHub answered ${response.status} to ${path}`)
    return response.json()
  }

const instanceClient =
  ({ baseUrl, token }) =>
  async (method, path, body) => {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: body ? { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } : {},
      body: body ? JSON.stringify(body) : undefined,
    })
    const text = await response.text()
    let parsed
    try {
      parsed = JSON.parse(text)
    } catch {
      parsed = text
    }
    if (method === 'GET' && !response.ok) throw new Error(`${path} answered ${response.status}`)
    return { status: response.status, body: parsed }
  }

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const env = process.env
  const missing = ['GITHUB_REPOSITORY', 'SEED_BASE_URL', 'INGEST_TOKEN'].filter(name => !env[name])
  if (missing.length > 0) {
    console.error(`missing ${missing.join(', ')}`)
    process.exitCode = 1
  } else {
    seed({
      repository: env.GITHUB_REPOSITORY,
      github: gitHubClient({ api: env.GITHUB_API_URL || 'https://api.github.com', token: env.GITHUB_TOKEN }),
      instance: instanceClient({ baseUrl: env.SEED_BASE_URL.replace(/\/$/, ''), token: env.INGEST_TOKEN }),
      ceiling: Number(env.SEED_NODE_CEILING || DEFAULT_CEILING),
    }).catch(error => {
      console.error(error.message)
      process.exitCode = 1
    })
  }
}
