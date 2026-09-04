<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="!repo" class="error">Repository not found.</div>
  <div v-else>
    <div class="detail-header">
      <div>
        <h1>{{ repo.orgRepo }}</h1>
        <p v-if="repo.description" class="desc">{{ repo.description }}</p>
      </div>
      <div class="header-actions">
        <router-link :to="`/repositories/${repo.id}/edit`" class="btn-secondary">Edit</router-link>
        <button class="btn-danger" @click="confirmDelete">Delete</button>
        <router-link to="/" class="btn-back">← Back</router-link>
      </div>
    </div>

    <div class="detail-grid">
      <!-- Metadata Card -->
      <div class="card">
        <h2>Metadata</h2>
        <table class="meta-table">
          <tr>
            <th>Branch</th>
            <td>{{ repo.defaultBranch }}</td>
          </tr>
          <tr v-if="repo.language">
            <th>Language</th>
            <td>{{ repo.language }}</td>
          </tr>
          <tr v-if="repo.serviceId">
            <th>ServiceNow CI</th>
            <td>{{ repo.serviceId }}</td>
          </tr>
          <tr v-if="repo.topics.length">
            <th>Topics</th>
            <td>{{ repo.topics.join(', ') }}</td>
          </tr>
          <tr v-if="repo.codeowners.length">
            <th>Codeowners</th>
            <td>{{ repo.codeowners.join(', ') }}</td>
          </tr>
        </table>
      </div>

      <!-- Impact Analysis Card -->
      <div class="card">
        <h2>Impact Analysis</h2>
        <div v-if="impact">
          <p class="impact-label">
            Downstream Dependents: <strong>{{ impact.dependents.length }}</strong>
          </p>
          <ul v-if="impact.dependents.length" class="item-list">
            <li v-for="d in impact.dependents" :key="d.id">{{ d.orgRepo }}</li>
          </ul>
          <p class="impact-label">
            Cloud Resources: <strong>{{ impact.cloudResources.length }}</strong>
          </p>
          <ul v-if="impact.cloudResources.length" class="item-list">
            <li v-for="r in impact.cloudResources" :key="r.id">
              {{ r.provider }}/{{ r.resourceType }}: {{ r.name }}
            </li>
          </ul>
          <p class="impact-label">
            Recent Deployments: <strong>{{ impact.deployments.length }}</strong>
          </p>
        </div>
        <p v-else class="muted">Loading impact data…</p>
      </div>

      <!-- Dependencies Card -->
      <div class="card">
        <h2>Dependencies</h2>
        <div v-if="dependencies.length === 0" class="muted">No upstream dependencies.</div>
        <ul v-else class="item-list">
          <li v-for="dep in dependencies" :key="dep.id">
            <router-link :to="`/repositories/${dep.id}`">{{ dep.orgRepo }}</router-link>
          </li>
        </ul>
      </div>

      <!-- Team Card -->
      <div class="card">
        <h2>Owning Team</h2>
        <div v-if="team">
          <p>
            <strong>{{ team.name }}</strong>
          </p>
          <p v-if="team.email" class="muted">{{ team.email }}</p>
        </div>
        <p v-else class="muted">No team assigned.</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  repositoryApi,
  graphApi,
  type Repository,
  type ImpactAnalysis,
  type Team
} from '@/services/api'

const route = useRoute()
const router = useRouter()
const id = route.params.id as string

const repo = ref<Repository | null>(null)
const loading = ref(true)
const impact = ref<ImpactAnalysis | null>(null)
const dependencies = ref<Repository[]>([])
const team = ref<Team | null>(null)

onMounted(async () => {
  try {
    repo.value = await repositoryApi.get(id)
    const [impactData, depsData, teamData] = await Promise.allSettled([
      graphApi.getImpact(id),
      graphApi.getDependencies(id),
      graphApi.getTeam(id)
    ])
    if (impactData.status === 'fulfilled') impact.value = impactData.value
    if (depsData.status === 'fulfilled') dependencies.value = depsData.value
    if (teamData.status === 'fulfilled') team.value = teamData.value
  } catch {
    // repo not found handled by null check
  } finally {
    loading.value = false
  }
})

async function confirmDelete() {
  if (confirm(`Delete repository ${repo.value?.orgRepo}?`)) {
    await repositoryApi.delete(id)
    router.push('/')
  }
}
</script>

<style scoped>
.loading,
.error {
  color: #718096;
  text-align: center;
  padding: 3rem;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1.5rem;
}
h1 {
  font-size: 1.5rem;
  color: #2d3748;
}
.desc {
  color: #718096;
  margin-top: 0.25rem;
}
.header-actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.btn-secondary {
  background: white;
  color: #4a5568;
  border: 1px solid #cbd5e0;
  padding: 0.4rem 0.9rem;
  border-radius: 6px;
  text-decoration: none;
  font-size: 0.85rem;
}
.btn-danger {
  background: #fff5f5;
  color: #e53e3e;
  border: 1px solid #fed7d7;
  padding: 0.4rem 0.9rem;
  border-radius: 6px;
  font-size: 0.85rem;
  cursor: pointer;
}
.btn-back {
  color: #718096;
  text-decoration: none;
  font-size: 0.85rem;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}
.card {
  background: white;
  border-radius: 10px;
  padding: 1.25rem;
  border: 1px solid #e2e8f0;
}
.card h2 {
  font-size: 1rem;
  color: #2d3748;
  margin-bottom: 1rem;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 0.5rem;
}
.meta-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
}
.meta-table th {
  text-align: left;
  color: #718096;
  width: 35%;
  padding: 0.3rem 0;
  font-weight: 500;
}
.meta-table td {
  color: #2d3748;
  padding: 0.3rem 0;
}
.impact-label {
  font-size: 0.85rem;
  color: #4a5568;
  margin: 0.5rem 0 0.25rem;
}
.item-list {
  list-style: none;
  padding: 0;
  margin: 0 0 0.75rem;
}
.item-list li {
  font-size: 0.85rem;
  color: #4a5568;
  padding: 0.2rem 0;
}
.item-list a {
  color: #2b6cb0;
  text-decoration: none;
}
.muted {
  color: #a0aec0;
  font-size: 0.85rem;
}
</style>
