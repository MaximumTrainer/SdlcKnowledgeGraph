import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  CONTEXT_PACK_TEMPLATES,
  EDGE_TYPES,
  NODE_TYPES,
  ONTOLOGY_VERSION,
  type ContextPackTemplate,
  type DeploymentStatus,
  type EdgeTypeName,
  type NodeType,
  type Repository
} from '@/generated/ontology'

/**
 * The generated module is the frontend's copy of the ontology. It is only worth having if it says
 * the same thing the registry says, so these tests compare it against the committed snapshot of the
 * `/api/v1/ontology` payload rather than restating the model a third time. Issue #20.
 */
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const read = (relativePath: string) => readFileSync(resolve(repoRoot, relativePath), 'utf8')
const snapshot = JSON.parse(read('backend/src/main/resources/ontology/v1/ontology.json'))

describe('generated ontology module', () => {
  it('declares the version the registry declares', () => {
    expect(ONTOLOGY_VERSION).toBe(snapshot.version)
  })

  it('lists every node type the registry declares, in registry order', () => {
    expect(NODE_TYPES).toEqual(snapshot.nodeTypes.map((type: { name: string }) => type.name))
  })

  it('covers the nine core types of the minimum viable graph', () => {
    // Named rather than counted. A total also caught a type being dropped, but it caught every type
    // being *added* just as loudly — including the meta types that describe the graph itself — so it
    // failed for the wrong reason every time the registry grew.
    for (const core of [
      'Repository',
      'Team',
      'Service',
      'Pipeline',
      'Artifact',
      'Deployment',
      'Environment',
      'CloudResource',
      'ConfigurationItem'
    ] as NodeType[]) {
      expect(NODE_TYPES).toContain(core)
    }
  })

  /** The graph describing itself: which ontology version built it, and what has been ingested. */
  it('declares the meta types the ingestion mechanism records into', () => {
    for (const meta of ['Ontology', 'SyncRun', 'ConnectorState'] as NodeType[]) {
      expect(NODE_TYPES).toContain(meta)
    }
  })

  it('lists every edge type the registry declares', () => {
    expect(EDGE_TYPES).toEqual(snapshot.edgeTypes.map((type: { name: string }) => type.name))
    expect(EDGE_TYPES).toContain('BUILT_FROM' as EdgeTypeName)
  })

  it('lists the context pack templates the registry declares, in registry order (#96)', () => {
    expect(CONTEXT_PACK_TEMPLATES).toEqual(
      snapshot.templates.map((template: { name: string }) => template.name)
    )
    expect(CONTEXT_PACK_TEMPLATES).toContain('change-impact' as ContextPackTemplate)
  })

  it('makes optional registry properties optional and required ones required', () => {
    // A compile-time assertion: `vue-tsc` fails the build if the generated shape disagrees.
    const repository: Repository = {
      id: 'Repository:github.com/acme/payments',
      url: 'https://github.com/acme/payments',
      host: 'github.com',
      org: 'acme',
      name: 'payments',
      defaultBranch: 'main',
      topics: [],
      codeowners: []
    }

    expect(repository.language).toBeUndefined()
  })

  it('announces itself as generated so it is not hand-edited', () => {
    expect(read('frontend/src/generated/ontology.ts')).toMatch(
      /^\/\/ GENERATED FROM ontology\/v1 - DO NOT EDIT/
    )
  })
})

interface SnapshotProperty {
  name: string
  description: string | null
  enum?: string[]
  examples: unknown[]
  deprecated?: { since: string; replacedBy: string | null }
}

interface SnapshotNodeType {
  name: string
  properties: SnapshotProperty[]
}

/** The block of `export interface <type> { ... }` in the generated module. */
const interfaceOf = (source: string, type: string) =>
  source.split(`export interface ${type} {`)[1]?.split('\n}')[0] ?? ''

/** The JSDoc written directly above a property in an interface block, or '' when there is none. */
const docOf = (block: string, property: string) => {
  const [before] = block.split(new RegExp(`\\n  ${property}\\??:`))
  const opened = before.lastIndexOf('/**')
  const closed = before.lastIndexOf('*/')
  return opened >= 0 && closed > opened && before.slice(closed).trim() === '*/'
    ? before.slice(opened)
    : ''
}

const capitalised = (name: string) => name.charAt(0).toUpperCase() + name.slice(1)

describe('generated ontology module, as a machine reader sees it (#81)', () => {
  const source = read('frontend/src/generated/ontology.ts')
  const nodeTypes = snapshot.nodeTypes as SnapshotNodeType[]

  it('declares a union type for every enum a node property has, listing its values', () => {
    for (const nodeType of nodeTypes) {
      for (const property of nodeType.properties.filter(p => p.enum)) {
        const name = `${nodeType.name}${capitalised(property.name)}`
        const declared = source.split(`export type ${name} =`)[1]?.split(/\n\n/)[0]
        expect(declared, `${name} is not generated`).toBeDefined()
        for (const value of property.enum ?? []) expect(declared).toContain(`'${value}'`)
      }
    }
  })

  it('types an enum property with its union', () => {
    // A compile-time assertion as much as a runtime one: `vue-tsc` fails if the union is missing.
    const status: DeploymentStatus = 'SUCCESS'
    expect(status).toBe('SUCCESS')
    expect(interfaceOf(source, 'Deployment')).toContain('status: DeploymentStatus')
  })

  it('documents every property with its description and first example', () => {
    for (const nodeType of nodeTypes) {
      const block = interfaceOf(source, nodeType.name)
      for (const property of nodeType.properties.filter(p => p.name !== 'id')) {
        const doc = docOf(block, property.name)
        expect(doc, `${nodeType.name}.${property.name} has no JSDoc`).not.toBe('')
        if (property.description) expect(doc).toContain(property.description)
        if (property.examples.length > 0) {
          expect(doc).toContain(`@example ${JSON.stringify(property.examples[0])}`)
        }
      }
    }
  })

  it('marks every deprecated property @deprecated, saying what replaces it', () => {
    const deprecated = nodeTypes.flatMap(type =>
      type.properties.filter(p => p.deprecated).map(p => [type.name, p] as const)
    )
    expect(deprecated.length).toBeGreaterThan(0)
    for (const [type, property] of deprecated) {
      const doc = docOf(interfaceOf(source, type), property.name)
      expect(doc, `${type}.${property.name}`).toContain(
        `@deprecated since ${property.deprecated?.since}`
      )
      if (property.deprecated?.replacedBy) expect(doc).toContain(property.deprecated.replacedBy)
    }
  })
})

describe('api service', () => {
  const api = read('frontend/src/services/api.ts')

  it('re-exports the node shapes instead of declaring its own', () => {
    expect(api).toContain("from '@/generated/ontology'")
    for (const shape of ['Repository', 'Team', 'Deployment', 'CloudResource']) {
      expect(api).not.toMatch(new RegExp(`interface ${shape}\b`))
    }
  })

  it('is guarded by a lint rule so the shapes cannot be redeclared elsewhere', () => {
    const eslint = read('frontend/eslint.config.js')
    expect(eslint).toContain('no-restricted-syntax')
    expect(eslint).toContain('src/generated/**')
  })
})
