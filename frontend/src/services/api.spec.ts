import { describe, expect, it } from 'vitest'
import { repositoryApi } from './api'
import { mockRepositories } from '@/test/msw/handlers'

describe('repositoryApi', () => {
  it('lists repositories from the API (mocked with MSW)', async () => {
    const repos = await repositoryApi.list()

    expect(repos).toHaveLength(mockRepositories.length)
    expect(repos.map(r => r.orgRepo)).toEqual(['acme/payments', 'acme/web'])
  })
})
