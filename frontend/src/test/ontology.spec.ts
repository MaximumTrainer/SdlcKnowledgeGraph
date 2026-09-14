import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  EDGE_TYPES,
  NODE_TYPES,
  ONTOLOGY_VERSION,
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
    expect(NODE_TYPES.length).toBe(11)
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

  it('lists every edge type the registry declares', () => {
    expect(EDGE_TYPES).toEqual(snapshot.edgeTypes.map((type: { name: string }) => type.name))
    expect(EDGE_TYPES).toContain('BUILT_FROM' as EdgeTypeName)
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
