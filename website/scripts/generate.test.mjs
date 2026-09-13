import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import {
  GENERATED_HEADER,
  adrIndexPage,
  apiPage,
  generatedFrom,
  ontologyPage,
  rewriteLinks
} from './generate.mjs'

/**
 * The generator turns repository sources into pages. These cover the parts that can be got wrong
 * quietly: a property omitted from a table, a link that still points at a path only GitHub
 * understands, or a page that stops declaring it was generated and gets hand-edited.
 */
const ontology = {
  version: '1.2.3',
  nodeTypes: [
    {
      name: 'Team',
      description: 'A group that owns things.',
      identity: ['name'],
      properties: [
        { name: 'name', type: 'string', required: true, description: 'The team name' },
        { name: 'email', type: 'string', required: false, description: null }
      ]
    }
  ],
  edgeTypes: [
    {
      name: 'OWNED_BY',
      description: 'Ownership.',
      from: ['Repository'],
      to: ['Team'],
      inverse: 'OWNS',
      properties: []
    }
  ]
}

describe('generatedFrom', () => {
  test('marks a page as generated and names what to run', () => {
    const header = generatedFrom('docs/ROADMAP.md')

    assert.match(header, /docs\/ROADMAP\.md/)
    assert.match(header, /DO NOT EDIT/)
    assert.match(header, /npm --prefix website run generate/)
  })

  test('is an HTML comment, so it does not render', () => {
    assert.match(generatedFrom('x'), /^<!--/)
    assert.match(generatedFrom('x').trim(), /-->$/)
  })

  test('every generated page carries the same marker', () => {
    assert.ok(generatedFrom('a').includes(GENERATED_HEADER))
  })
})

describe('ontologyPage', () => {
  const page = ontologyPage(ontology)

  test('documents every node type with its identity', () => {
    assert.ok(page.includes('## Team'))
    assert.ok(page.includes('A group that owns things.'))
    assert.ok(page.includes('name'))
  })

  test('documents every property, its type and whether it is required', () => {
    assert.ok(page.includes('| `name` | `string` | yes | The team name |'))
    assert.ok(page.includes('| `email` | `string` | no |'))
  })

  test('documents every edge type with its endpoints and inverse', () => {
    assert.ok(page.includes('OWNED_BY'))
    assert.ok(page.includes('OWNS'))
    assert.ok(page.includes('Repository'))
  })

  test('states the ontology version the page describes', () => {
    assert.ok(page.includes('1.2.3'))
  })

  test('is generated, and says so', () => {
    assert.ok(page.includes('DO NOT EDIT'))
  })
})

describe('adrIndexPage', () => {
  const records = [
    { file: '0001-lefthook-git-hooks.md', title: 'ADR-0001: Git hooks with lefthook', status: 'Accepted.' },
    { file: '0004-pact-folder-no-broker.md', title: 'ADR-0004: Verify Pact contracts from a folder', status: 'Accepted. Implemented by #17.' }
  ]
  const page = adrIndexPage(records)

  test('lists every record with its number and title', () => {
    assert.ok(page.includes('0001'))
    assert.ok(page.includes('Git hooks with lefthook'))
    assert.ok(page.includes('0004'))
  })

  test('reduces a status sentence to its decision', () => {
    assert.ok(page.includes('| Accepted |'))
    assert.ok(!page.includes('Implemented by #17'))
  })

  test('links each record to its own page', () => {
    assert.ok(page.includes('./0004-pact-folder-no-broker'))
  })
})

describe('apiPage', () => {
  const page = apiPage({
    info: { title: 'SDLC', version: '1' },
    paths: {
      '/api/v1/nodes/{type}': {
        get: { summary: 'List nodes', responses: { 200: {}, 404: {} } },
        post: { summary: 'Create a node', responses: { 201: {} } }
      }
    }
  })

  test('lists every path and method', () => {
    assert.ok(page.includes('/api/v1/nodes/{type}'))
    assert.ok(page.includes('GET'))
    assert.ok(page.includes('POST'))
  })

  test('carries the summary and the statuses a caller must handle', () => {
    assert.ok(page.includes('List nodes'))
    assert.ok(page.includes('201'))
    assert.ok(page.includes('404'))
  })
})

describe('rewriteLinks', () => {
  test('turns a GitHub issue shorthand into a URL', () => {
    const out = rewriteLinks('See [#20](../../issues/20).', 'docs/ROADMAP.md')

    assert.ok(out.includes('https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/20'))
    assert.ok(!out.includes('../../issues/20'))
  })

  test('handles the deeper shorthand used inside adr files', () => {
    const out = rewriteLinks('[#17](../../../issues/17)', 'docs/adr/0004-x.md')

    assert.ok(out.includes('https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/17'))
  })

  test('maps a doc link from the README onto its published path', () => {
    const out = rewriteLinks('[roadmap](docs/ROADMAP.md) and [ontology](docs/ONTOLOGY.md)', 'README.md')

    assert.ok(out.includes('(/reference/roadmap)'))
    assert.ok(out.includes('(/guide/ontology)'))
  })

  test('maps a sibling doc link', () => {
    const out = rewriteLinks('[testing](TESTING.md)', 'docs/ONTOLOGY.md')

    assert.ok(out.includes('(/guide/testing)'))
  })

  test('keeps an anchor when rewriting', () => {
    const out = rewriteLinks('[contracts](../TESTING.md#contract-tests)', 'docs/adr/0004-x.md')

    assert.ok(out.includes('(/guide/testing#contract-tests)'))
  })

  test('maps a link into the adr folder', () => {
    const fromDocs = rewriteLinks('[adr](adr/0004-pact-folder-no-broker.md)', 'docs/TESTING.md')
    const fromReadme = rewriteLinks('[adrs](docs/adr/)', 'README.md')

    assert.ok(fromDocs.includes('(/adr/0004-pact-folder-no-broker)'))
    assert.ok(fromReadme.includes('(/adr/)'))
  })

  test('leaves an external link alone', () => {
    const link = '[neo4j](https://neo4j.com/docs)'

    assert.equal(rewriteLinks(link, 'README.md'), link)
  })

  test('leaves a bare anchor alone', () => {
    const link = '[below](#contract-tests)'

    assert.equal(rewriteLinks(link, 'docs/TESTING.md'), link)
  })

  test('names the page when the link text is the path itself', () => {
    const out = rewriteLinks('[docs/TESTING.md](docs/TESTING.md) explains the loop.', 'README.md')

    assert.ok(out.includes('[Testing](/guide/testing)'))
    assert.ok(!out.includes('docs/TESTING.md'))
  })

  test('names the page for every published doc', () => {
    const out = rewriteLinks(
      '[ONTOLOGY.md](ONTOLOGY.md) [ADAPTERS.md](ADAPTERS.md) [docs/ROADMAP.md](docs/ROADMAP.md)',
      'docs/TESTING.md'
    )

    assert.ok(out.includes('[Ontology](/guide/ontology)'))
    assert.ok(out.includes('[Adapters](/guide/adapters)'))
    assert.ok(out.includes('[Roadmap](/reference/roadmap)'))
  })

  test('names the decisions index when the link text is the adr folder', () => {
    const out = rewriteLinks('[docs/adr/](docs/adr/)', 'README.md')

    assert.ok(out.includes('[Decisions](/adr/)'))
  })

  test('keeps the anchor when it names the page', () => {
    const out = rewriteLinks('[TESTING.md](../TESTING.md#contract-tests)', 'docs/adr/0004-x.md')

    assert.ok(out.includes('[Testing](/guide/testing#contract-tests)'))
  })

  test('leaves descriptive link text exactly as written', () => {
    const out = rewriteLinks(
      '[the testing guide](docs/TESTING.md) and [ADR-0001](adr/0001-lefthook-git-hooks.md)',
      'README.md'
    )

    assert.ok(out.includes('[the testing guide](/guide/testing)'))
    assert.ok(out.includes('[ADR-0001](/adr/0001-lefthook-git-hooks)'))
  })

  test('does not rename a path it cannot publish', () => {
    const link = '[scripts/gradle.mjs](scripts/gradle.mjs)'

    assert.equal(rewriteLinks(link, 'README.md'), link)
  })

  test('does not rewrite inside a fenced code block', () => {
    const source = ['```bash', 'cat docs/ROADMAP.md', '```'].join('\n')

    assert.equal(rewriteLinks(source, 'README.md'), source)
  })
})
