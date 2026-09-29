import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildSeed,
  dependenciesFromGradle,
  dependenciesFromPackageJson,
  parseCodeowners,
  seed,
} from './dogfood-seed.mjs'

/**
 * The dogfood seed (#47, FR10, FR11): what it reads about this repository from GitHub, the batch it
 * posts to POST /api/v1/ingest/seed (docs/ADAPTERS.md, "Seeding"), and how it fails.
 */
describe('dogfood-seed', () => {
  const repository = {
    fullName: 'MaximumTrainer/SdlcKnowledgeGraph',
    defaultBranch: 'main',
    topics: ['knowledge-graph'],
    description: 'SDLC knowledge graph',
    language: 'Kotlin',
    visibility: 'public',
  }
  const repoKey = 'github.com/maximumtrainer/sdlcknowledgegraph'

  describe('parseCodeowners', () => {
    test('reads each rule, skipping comments and blank lines', () => {
      const rules = parseCodeowners(
        ['# Default owner', '', '*   @MaximumTrainer', '/docs/ @acme/writers @MaximumTrainer  # trailing', '/secret'].join('\n'),
      )

      assert.deepEqual(rules, [
        { pattern: '*', owners: ['@MaximumTrainer'] },
        { pattern: '/docs/', owners: ['@acme/writers', '@MaximumTrainer'] },
      ])
    })
  })

  describe('dependencies', () => {
    test('keeps only package.json dependencies that resolve to a git remote', () => {
      const manifest = JSON.stringify({
        dependencies: { vue: '^3.5.0', kit: 'github:Acme/UI-Kit#v2', '@acme/charts': 'git+https://github.com/acme/charts.git' },
        devDependencies: { lint: 'acme/lint-rules', local: 'file:../local', sibling: '../sibling', tarball: 'https://example.com/x.tgz' },
      })

      assert.deepEqual(dependenciesFromPackageJson('frontend/package.json', manifest), [
        { remote: 'github.com/acme/ui-kit', manifest: 'frontend/package.json', version: 'v2' },
        { remote: 'github.com/acme/charts', manifest: 'frontend/package.json', version: 'git+https://github.com/acme/charts.git' },
        { remote: 'github.com/acme/lint-rules', manifest: 'frontend/package.json', version: 'acme/lint-rules' },
      ])
    })

    test('keeps only Gradle source dependencies, which name a git remote', () => {
      const build = [
        'dependencies { implementation("org.springframework.boot:spring-boot-starter-web") }',
        'sourceControl { gitRepository(uri("https://github.com/acme/shared-kotlin.git")) { producesModule("acme:shared") } }',
      ].join('\n')

      assert.deepEqual(dependenciesFromGradle('backend/build.gradle.kts', build), [
        { remote: 'github.com/acme/shared-kotlin', manifest: 'backend/build.gradle.kts', version: undefined },
      ])
    })

    test('reads nothing from a manifest that is not there', () => {
      assert.deepEqual(dependenciesFromPackageJson('frontend/package.json', null), [])
      assert.deepEqual(dependenciesFromGradle('backend/build.gradle.kts', null), [])
    })
  })

  describe('buildSeed', () => {
    const input = () => ({
      repository,
      codeowners: parseCodeowners('* @MaximumTrainer\n/docs/ @acme/writers'),
      workflows: [
        { path: '.github/workflows/ci.yml', name: 'CI', lastRunStatus: 'success' },
        { path: '.github/workflows/release.yml', name: 'Release', lastRunStatus: undefined },
      ],
      dependencies: [
        {
          remote: 'github.com/acme/ui-kit',
          manifest: 'frontend/package.json',
          version: 'v2',
          repository: { fullName: 'acme/ui-kit', defaultBranch: 'main', topics: [] },
        },
      ],
    })
    const batch = buildSeed(input())
    const node = index => batch.nodes[index]

    test('records the repository first, with what the ontology requires of it', () => {
      assert.deepEqual(node(0), {
        type: 'Repository',
        sourceId: 'https://github.com/MaximumTrainer/SdlcKnowledgeGraph',
        props: {
          url: 'https://github.com/MaximumTrainer/SdlcKnowledgeGraph',
          defaultBranch: 'main',
          topics: ['knowledge-graph'],
          codeowners: ['@MaximumTrainer', '@acme/writers'],
          description: 'SDLC knowledge graph',
          language: 'Kotlin',
          visibility: 'public',
        },
      })
    })

    test('records a team per CODEOWNERS owner, owning the repository by the patterns it was named against', () => {
      const teams = batch.nodes.filter(n => n.type === 'Team').map(n => n.props.name)
      assert.deepEqual(teams, ['maximumtrainer', 'acme/writers'])

      const owned = batch.edges.filter(e => e.type === 'OWNED_BY')
      assert.deepEqual(
        owned.map(e => [e.from, node(e.to).props.name, e.props.pathPatterns]),
        [
          [0, 'maximumtrainer', ['*']],
          [0, 'acme/writers', ['/docs/']],
        ],
      )
    })

    test('records a pipeline per workflow, with its last run status when there was a run', () => {
      const pipelines = batch.nodes.filter(n => n.type === 'Pipeline')
      assert.deepEqual(pipelines[0].props, {
        provider: 'github-actions',
        repoKey,
        workflowPath: '.github/workflows/ci.yml',
        name: 'CI',
        repoId: repoKey,
        lastRunStatus: 'success',
      })
      assert.equal('lastRunStatus' in pipelines[1].props, false)
      assert.equal(batch.edges.filter(e => e.type === 'HAS_PIPELINE' && e.from === 0).length, 2)
    })

    test('records a dependency on another repository with its kind and manifest', () => {
      const [dependsOn] = batch.edges.filter(e => e.type === 'DEPENDS_ON')
      assert.equal(dependsOn.from, 0)
      assert.equal(node(dependsOn.to).props.url, 'https://github.com/acme/ui-kit')
      assert.deepEqual(dependsOn.props, { kind: 'library', manifest: 'frontend/package.json', version: 'v2' })
    })

    test('builds the same batch from the same input, so a rerun is one delivery', () => {
      assert.equal(JSON.stringify(buildSeed(input())), JSON.stringify(batch))
    })
  })

  describe('seed', () => {
    /** GitHub as the seed reads it: this repository, its CODEOWNERS, two workflows and their runs. */
    const fakeGitHub = () => {
      const files = {
        '.github/CODEOWNERS': '* @MaximumTrainer\n',
        'frontend/package.json': JSON.stringify({ dependencies: { vue: '^3.5.0' } }),
      }
      return async path => {
        if (path === '/repos/MaximumTrainer/SdlcKnowledgeGraph') {
          return { full_name: repository.fullName, default_branch: 'main', topics: [], visibility: 'public' }
        }
        if (path.startsWith('/repos/MaximumTrainer/SdlcKnowledgeGraph/contents/')) {
          const file = decodeURIComponent(path.split('/contents/')[1].split('?')[0])
          return file in files ? { content: Buffer.from(files[file]).toString('base64'), encoding: 'base64' } : null
        }
        if (path.startsWith('/repos/MaximumTrainer/SdlcKnowledgeGraph/actions/workflows?')) {
          return {
            workflows: [
              { id: 1, name: 'CI', path: '.github/workflows/ci.yml' },
              { id: 2, name: 'pages-build-deployment', path: 'dynamic/pages/pages-build-deployment' },
            ],
          }
        }
        if (path.startsWith('/repos/MaximumTrainer/SdlcKnowledgeGraph/actions/workflows/1/runs')) {
          return { workflow_runs: [{ status: 'completed', conclusion: 'success' }] }
        }
        throw new Error(`unexpected GitHub request ${path}`)
      }
    }

    /** The instance, keeping nodes and edges by identity the way the server derives it, closely enough. */
    const fakeInstance = ({ reachable = true, extraNodes = 0 } = {}) => {
      const nodes = new Map()
      const edges = new Set()
      const posts = []
      const identity = n => `${n.type}:${n.props.url?.toLowerCase() ?? n.props.workflowPath ?? n.props.name}`
      const request = async (method, path, body) => {
        if (!reachable) throw new Error('connect ECONNREFUSED')
        if (method === 'GET' && path === '/actuator/health') return { status: 200, body: { status: 'UP' } }
        if (method === 'POST' && path === '/api/v1/ingest/seed') {
          posts.push(body)
          const keys = body.nodes.map(identity)
          body.nodes.forEach((n, i) => nodes.set(keys[i], n))
          body.edges.forEach(e => edges.add(`${keys[e.from]}-${e.type}-${keys[e.to]}`))
          return { status: 202, body: { created: true, nodes: body.nodes.length, edges: body.edges.length } }
        }
        if (method === 'GET' && path === '/api/v1/ontology') {
          return { status: 200, body: { nodeTypes: [{ name: 'Repository' }, { name: 'Team' }, { name: 'Pipeline' }, { name: 'SyncRun', meta: true }] } }
        }
        const listed = path.match(/^\/api\/v1\/nodes\/(\w+)\?limit=\d+/)
        if (method === 'GET' && listed) {
          const items = [...nodes.keys()].filter(k => k.startsWith(`${listed[1]}:`))
          const padding = listed[1] === 'Team' ? Array.from({ length: extraNodes }, (_, i) => `Team:extra-${i}`) : []
          return { status: 200, body: { items: [...items, ...padding].map(id => ({ id })), nextCursor: null } }
        }
        throw new Error(`unexpected instance request ${method} ${path}`)
      }
      return { request, nodes, edges, posts }
    }

    const options = (instance, overrides = {}) => ({
      repository: repository.fullName,
      github: fakeGitHub(),
      instance: instance.request,
      ceiling: 100,
      log: () => {},
      ...overrides,
    })

    test('seeding twice leaves the node and edge counts unchanged', async () => {
      const instance = fakeInstance()

      await seed(options(instance))
      const counts = [instance.nodes.size, instance.edges.size]
      await seed(options(instance))

      assert.deepEqual([instance.nodes.size, instance.edges.size], counts)
      assert.deepEqual(counts, [3, 2])
    })

    test('seeds only the workflows that are files in the repository', async () => {
      const instance = fakeInstance()

      await seed(options(instance))

      const pipelines = instance.posts[0].nodes.filter(n => n.type === 'Pipeline')
      assert.deepEqual(
        pipelines.map(p => p.props.workflowPath),
        ['.github/workflows/ci.yml'],
      )
    })

    test('fails loudly when the instance cannot be reached', async () => {
      await assert.rejects(seed(options(fakeInstance({ reachable: false }))), /cannot reach the instance/)
    })

    test('fails when the instance holds more nodes than the ceiling', async () => {
      await assert.rejects(
        seed(options(fakeInstance({ extraNodes: 200 }), { ceiling: 100 })),
        /the instance holds 203 nodes, more than the ceiling of 100/,
      )
    })

    test('fails with the instance\'s answer when the seed is refused', async () => {
      const refusing = {
        request: async (method, path) =>
          path === '/actuator/health'
            ? { status: 200, body: {} }
            : { status: 400, body: { error: 'invalid seed', fields: { 'nodes[0].type': 'X cannot be seeded' } } },
      }

      await assert.rejects(seed(options(refusing)), /the seed was refused \(400\).*X cannot be seeded/)
    })
  })
})
