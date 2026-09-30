import { http, HttpResponse } from 'msw'
import type { Repository } from '@/services/api'

export const mockRepositories: Repository[] = [
  {
    id: 'repo-1',
    url: 'https://github.com/acme/payments',
    host: 'github.com',
    org: 'acme',
    name: 'payments',
    defaultBranch: 'main',
    topics: ['payments', 'critical'],
    codeowners: ['@acme/payments-team'],
    language: 'Kotlin',
    description: 'Payments service'
  },
  {
    id: 'repo-2',
    url: 'https://github.com/acme/web',
    host: 'github.com',
    org: 'acme',
    name: 'web',
    defaultBranch: 'main',
    topics: [],
    codeowners: [],
    language: 'TypeScript'
  }
]

/** Default request handlers shared by every unit test. */
export const handlers = [
  http.get('/api/v1/repositories', () => HttpResponse.json(mockRepositories)),
  http.get('/api/v1/repositories/:id', ({ params }) => {
    const repo = mockRepositories.find(r => r.id === params.id)
    return repo ? HttpResponse.json(repo) : new HttpResponse(null, { status: 404 })
  }),
  // Every source within its window: the pages that show lag (#93) have nothing to warn about.
  http.get('/api/v1/freshness', () => HttpResponse.json({ sources: [] })),
  // A node that has never changed (#33): its page shows its history with nothing earlier in it.
  http.get('/api/v1/lifecycle/history', ({ request }) =>
    HttpResponse.json({
      nodeId: new URL(request.url).searchParams.get('nodeId'),
      current: {
        validFrom: '2026-01-01T00:00:00Z',
        validTo: null,
        propsFrom: '2026-01-01T00:00:00Z',
        retired: false,
        retiredReason: null,
        resurrectedAt: null,
        props: {}
      },
      versions: []
    })
  )
]
