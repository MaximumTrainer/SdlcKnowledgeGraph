import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import { ontologyApi, type Ontology } from './api'

const ontology: Ontology = {
  version: '1.0.0',
  nodeTypes: [
    {
      name: 'Repository',
      description: 'A git repository',
      identity: ['host', 'org', 'name'],
      properties: [
        { name: 'host', type: 'string', required: true, description: 'Host of the remote' },
        { name: 'topics', type: 'string[]', required: false, description: null }
      ]
    }
  ],
  edgeTypes: [
    {
      name: 'BUILT_FROM',
      description: null,
      from: ['Artifact'],
      to: ['Repository'],
      inverse: 'BUILDS',
      properties: []
    }
  ]
}

describe('ontologyApi', () => {
  it('reads the whole ontology so screens can render themselves from it', async () => {
    server.use(http.get('/api/v1/ontology', () => HttpResponse.json(ontology)))

    const result = await ontologyApi.get()

    expect(result.version).toBe('1.0.0')
    expect(result.nodeTypes[0].identity).toEqual(['host', 'org', 'name'])
    expect(result.nodeTypes[0].properties[1].type).toBe('string[]')
    expect(result.edgeTypes[0].inverse).toBe('BUILDS')
  })

  it('reads a single node type', async () => {
    server.use(
      http.get('/api/v1/ontology/nodes/Repository', () => HttpResponse.json(ontology.nodeTypes[0]))
    )

    const nodeType = await ontologyApi.getNodeType('Repository')

    expect(nodeType.name).toBe('Repository')
    expect(nodeType.properties.filter(p => p.required).map(p => p.name)).toEqual(['host'])
  })

  it('surfaces an unknown node type as a failure rather than an empty form', async () => {
    server.use(
      http.get('/api/v1/ontology/nodes/Nonsense', () =>
        HttpResponse.json({ error: 'unknown node type', type: 'Nonsense' }, { status: 404 })
      )
    )

    await expect(ontologyApi.getNodeType('Nonsense')).rejects.toThrow()
  })
})
