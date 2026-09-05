<template>
  <div>
    <div class="page-header">
      <h1>Git Repositories</h1>
      <span class="badge">{{ repos.length }} registered</span>
    </div>

    <div v-if="loading" class="loading">Loading…</div>
    <div v-else-if="error" class="error">{{ error }}</div>
    <div v-else-if="repos.length === 0" class="empty">
      No repositories yet. <router-link to="/repositories/new">Register one →</router-link>
    </div>

    <div v-else class="repo-grid">
      <router-link
        v-for="repo in repos"
        :key="repo.id"
        :to="`/repositories/${repo.id}`"
        class="repo-card"
      >
        <div class="repo-name">{{ repo.orgRepo }}</div>
        <div class="repo-meta">
          <span class="tag">{{ repo.defaultBranch }}</span>
          <span v-if="repo.language" class="tag lang">{{ repo.language }}</span>
          <span v-for="topic in repo.topics.slice(0, 3)" :key="topic" class="tag topic">{{
            topic
          }}</span>
        </div>
        <p v-if="repo.description" class="repo-desc">{{ repo.description }}</p>
        <div v-if="repo.serviceId" class="service-id">ServiceNow: {{ repo.serviceId }}</div>
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { repositoryApi, type Repository } from '@/services/api'

const repos = ref<Repository[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    repos.value = await repositoryApi.list()
  } catch {
    error.value = 'Failed to load repositories. Is the backend running?'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1.5rem;
}
h1 {
  font-size: 1.5rem;
  color: #2d3748;
}
.badge {
  background: #ebf8ff;
  color: #2b6cb0;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 0.8rem;
  font-weight: 600;
}
.loading,
.empty {
  color: #718096;
  text-align: center;
  padding: 3rem;
}
.error {
  color: #e53e3e;
  padding: 1rem;
  background: #fff5f5;
  border-radius: 8px;
}
.repo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1rem;
}
.repo-card {
  background: white;
  border-radius: 10px;
  padding: 1.25rem;
  text-decoration: none;
  color: inherit;
  border: 1px solid #e2e8f0;
  transition: box-shadow 0.2s;
  display: block;
}
.repo-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}
.repo-name {
  font-size: 1rem;
  font-weight: 600;
  color: #2b6cb0;
  margin-bottom: 0.5rem;
}
.repo-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-bottom: 0.5rem;
}
.tag {
  background: #edf2f7;
  color: #4a5568;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.75rem;
}
.lang {
  background: #e6fffa;
  color: #2c7a7b;
}
.topic {
  background: #ebf4ff;
  color: #2c5282;
}
.repo-desc {
  font-size: 0.85rem;
  color: #718096;
  margin-top: 0.5rem;
}
.service-id {
  font-size: 0.75rem;
  color: #805ad5;
  margin-top: 0.5rem;
}
</style>
