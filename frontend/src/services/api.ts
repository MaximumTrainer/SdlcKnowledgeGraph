import axios from 'axios'

const client = axios.create({ baseURL: '/api/v1' })

export interface Repository {
  id: string
  orgRepo: string
  defaultBranch: string
  topics: string[]
  codeowners: string[]
  serviceId?: string
  language?: string
  description?: string
}

export interface CloudResource {
  id: string
  provider: string
  resourceType: string
  name: string
  region?: string
}

export interface Deployment {
  id: string
  artifactId: string
  environmentId: string
  deployedAt: string
  deployedBy?: string
  status: string
}

export interface Team {
  id: string
  name: string
  email?: string
}

export interface ImpactAnalysis {
  repoId: string
  dependents: Repository[]
  cloudResources: CloudResource[]
  deployments: Deployment[]
}

export const repositoryApi = {
  list: (): Promise<Repository[]> => client.get('/repositories').then(r => r.data),
  get: (id: string): Promise<Repository> => client.get(`/repositories/${id}`).then(r => r.data),
  create: (repo: Omit<Repository, 'id'>): Promise<Repository> =>
    client.post('/repositories', repo).then(r => r.data),
  delete: (id: string): Promise<void> => client.delete(`/repositories/${id}`).then(() => undefined),
  linkToTeam: (repoId: string, teamId: string): Promise<void> =>
    client.post(`/repositories/${repoId}/teams/${teamId}`).then(() => undefined),
  addDependency: (repoId: string, depRepoId: string): Promise<void> =>
    client.post(`/repositories/${repoId}/dependencies/${depRepoId}`).then(() => undefined)
}

export const graphApi = {
  getCloudResources: (repoId: string): Promise<CloudResource[]> =>
    client.get(`/graph/repositories/${repoId}/cloud-resources`).then(r => r.data),
  getDependencies: (repoId: string): Promise<Repository[]> =>
    client.get(`/graph/repositories/${repoId}/dependencies`).then(r => r.data),
  getDependents: (repoId: string): Promise<Repository[]> =>
    client.get(`/graph/repositories/${repoId}/dependents`).then(r => r.data),
  getDeployments: (repoId: string): Promise<Deployment[]> =>
    client.get(`/graph/repositories/${repoId}/deployments`).then(r => r.data),
  getTeam: (repoId: string): Promise<Team | null> =>
    client
      .get(`/graph/repositories/${repoId}/team`)
      .then(r => r.data)
      .catch(() => null),
  getImpact: (repoId: string): Promise<ImpactAnalysis> =>
    client.get(`/graph/repositories/${repoId}/impact`).then(r => r.data)
}
