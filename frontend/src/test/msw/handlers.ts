import { http, HttpResponse } from 'msw'
import type { Repository } from '@/services/api'

export const mockRepositories: Repository[] = [
  {
    id: 'repo-1',
    orgRepo: 'acme/payments',
    defaultBranch: 'main',
    topics: ['payments', 'critical'],
    codeowners: ['@acme/payments-team'],
    language: 'Kotlin',
    description: 'Payments service'
  },
  {
    id: 'repo-2',
    orgRepo: 'acme/web',
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
  })
]
