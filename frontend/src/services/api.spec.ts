import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import { graphApi, impactApi, repositoryApi } from './api'
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
