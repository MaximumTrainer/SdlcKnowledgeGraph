import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import { graphApi, impactApi, lineageApi, nodeApi, repositoryApi } from './api'
import { mockRepositories } from '@/test/msw/handlers'

describe('repositoryApi', () => {
  it('lists repositories from the API (mocked with MSW)', async () => {
    const repos = await repositoryApi.list()

    expect(repos).toHaveLength(mockRepositories.length)
    expect(repos.map(r => r.url)).toEqual([
      'https://github.com/acme/payments',
      'https://github.com/acme/web'
    ])
  })
})

describe('graphApi.neighbourhood (#9)', () => {
  const answer = { root: 'Team:platform', nodes: [], edges: [], truncated: false }

  it('sends the node, the depth and the direction, defaulting to 1 and both', async () => {
    let asked: URL | null = null
    server.use(
      http.get('/api/v1/graph/neighbourhood', ({ request }) => {
        asked = new URL(request.url)
        return HttpResponse.json(answer)
      })
    )

    expect(await graphApi.neighbourhood({ nodeId: 'Team:platform' })).toEqual(answer)
    expect(Object.fromEntries(asked!.searchParams)).toEqual({
      nodeId: 'Team:platform',
      depth: '1',
      direction: 'both'
    })
  })

  it('sends the type filters comma separated, and only when there are any', async () => {
    let asked: URL | null = null
    server.use(
      http.get('/api/v1/graph/neighbourhood', ({ request }) => {
        asked = new URL(request.url)
        return HttpResponse.json(answer)
      })
    )

    await graphApi.neighbourhood({
      nodeId: 'Repository:github.com/acme/payments',
      depth: 2,
      nodeTypes: ['Repository', 'Team'],
      edgeTypes: [],
      direction: 'in'
    })

    expect(Object.fromEntries(asked!.searchParams)).toEqual({
      nodeId: 'Repository:github.com/acme/payments',
      depth: '2',
      nodeTypes: 'Repository,Team',
      direction: 'in'
    })
  })
})

describe('impactApi.impact (#21, for #9)', () => {
  it('asks for the blast radius of a node at a depth and a confidence floor', async () => {
    let asked: URL | null = null
    server.use(
      http.get('/api/v1/graph/impact', ({ request }) => {
        asked = new URL(request.url)
        return HttpResponse.json({ affected: [], byType: { excluded: 0 } })
      })
    )

    await impactApi.impact('Repository:github.com/acme/shared-lib', 2, 0.5)

    expect(Object.fromEntries(asked!.searchParams)).toEqual({
      nodeId: 'Repository:github.com/acme/shared-lib',
      depth: '2',
      minConfidence: '0.5'
    })
  })
})

/**
 * A key holding the `//` of a URI cannot travel in a path (#85): the server's firewall refuses it and
 * a proxy may merge it away. Such a node is read, replaced and deleted by key as a query parameter;
 * every other key keeps the path it always had.
 */
describe('nodeApi, for a key a path cannot carry (#85)', () => {
  const workItem = {
    id: 'ExternalWorkItem:chorus://task/01JABC',
    type: 'ExternalWorkItem',
    key: 'chorus://task/01JABC',
    props: { uri: 'chorus://task/01JABC', system: 'chorus' },
    provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
  }

  it('reads, replaces and deletes by key', async () => {
    const asked: string[] = []
    const record = (request: Request) => {
      const url = new URL(request.url)
      asked.push(`${request.method} ${url.pathname} ${url.searchParams.get('key')}`)
    }
    server.use(
      http.get('/api/v1/nodes/ExternalWorkItem/by-key', ({ request }) => {
        record(request)
        return HttpResponse.json(workItem)
      }),
      http.put('/api/v1/nodes/ExternalWorkItem/by-key', ({ request }) => {
        record(request)
        return HttpResponse.json(workItem)
      }),
      http.delete('/api/v1/nodes/ExternalWorkItem/by-key', ({ request }) => {
        record(request)
        return new HttpResponse(null, { status: 204 })
      })
    )

    expect((await nodeApi.get('ExternalWorkItem', 'chorus://task/01JABC')).key).toBe(
      'chorus://task/01JABC'
    )
    await nodeApi.update('ExternalWorkItem', 'chorus://task/01JABC', workItem.props)
    await nodeApi.remove('ExternalWorkItem', 'chorus://task/01JABC')

    expect(asked).toEqual([
      'GET /api/v1/nodes/ExternalWorkItem/by-key chorus://task/01JABC',
      'PUT /api/v1/nodes/ExternalWorkItem/by-key chorus://task/01JABC',
      'DELETE /api/v1/nodes/ExternalWorkItem/by-key chorus://task/01JABC'
    ])
  })

  it('keeps the path for a key with single slashes', async () => {
    let asked = ''
    server.use(
      http.get('/api/v1/nodes/Change/*', ({ request }) => {
        asked = new URL(request.url).pathname
        return HttpResponse.json({ ...workItem, type: 'Change' })
      })
    )

    await nodeApi.get('Change', 'github.com/acme/payments@a1b2c3')

    expect(asked).toBe('/api/v1/nodes/Change/github.com/acme/payments@a1b2c3')
  })
})

describe('lineageApi (#85)', () => {
  it('asks where a work item is live by its uri', async () => {
    let asked: URL | null = null
    const answer = { workItem: { id: 'ExternalWorkItem:chorus://task/01JABC' }, deployments: [] }
    server.use(
      http.get('/api/v1/work-items/deployments', ({ request }) => {
        asked = new URL(request.url)
        return HttpResponse.json(answer)
      })
    )

    expect(await lineageApi.deploymentsOfWorkItem('chorus://task/01JABC')).toEqual(answer)
    expect(Object.fromEntries(asked!.searchParams)).toEqual({ uri: 'chorus://task/01JABC' })
  })

  it('asks what a deployment carries by its id', async () => {
    let asked: URL | null = null
    const answer = {
      deployment: { id: 'Deployment:d1' },
      lineage: 'unknown',
      changes: [],
      workItems: []
    }
    server.use(
      http.get('/api/v1/deployments/work-items', ({ request }) => {
        asked = new URL(request.url)
        return HttpResponse.json(answer)
      })
    )

    expect(await lineageApi.workItemsOfDeployment('Deployment:a/b:1#production#1')).toEqual(answer)
    expect(Object.fromEntries(asked!.searchParams)).toEqual({
      deploymentId: 'Deployment:a/b:1#production#1'
    })
  })
})
