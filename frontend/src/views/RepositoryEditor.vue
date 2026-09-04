<template>
  <div class="editor-page">
    <h1>{{ isEdit ? 'Edit Repository' : 'Register Repository' }}</h1>

    <form class="editor-form" @submit.prevent="submit">
      <div class="field">
        <label>Repository (org/repo) *</label>
        <input v-model="form.orgRepo" placeholder="e.g. myorg/my-service" required />
      </div>

      <div class="field">
        <label>Default Branch</label>
        <input v-model="form.defaultBranch" placeholder="main" />
      </div>

      <div class="field">
        <label>Language</label>
        <input v-model="form.language" placeholder="e.g. Kotlin, Python, TypeScript" />
      </div>

      <div class="field">
        <label>Description</label>
        <textarea v-model="form.description" rows="3" placeholder="What does this repo do?" />
      </div>

      <div class="field">
        <label>Topics (comma-separated)</label>
        <input v-model="topicsInput" placeholder="e.g. payments, microservice, critical" />
      </div>

      <div class="field">
        <label>Codeowners (comma-separated)</label>
        <input v-model="codeownersInput" placeholder="e.g. @team-payments, @jane" />
      </div>

      <div class="field">
        <label>ServiceNow Service ID</label>
        <input v-model="form.serviceId" placeholder="e.g. SVC0001234" />
      </div>

      <div class="actions">
        <button type="submit" class="btn-primary" :disabled="saving">
          {{ saving ? 'Saving…' : isEdit ? 'Save Changes' : 'Register Repository' }}
        </button>
        <router-link to="/" class="btn-secondary">Cancel</router-link>
      </div>

      <div v-if="error" class="error">{{ error }}</div>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { isAxiosError } from 'axios'
import { repositoryApi } from '@/services/api'

const router = useRouter()
const route = useRoute()
const id = route.params.id as string | undefined
const isEdit = computed(() => !!id)

const form = ref({
  orgRepo: '',
  defaultBranch: 'main',
  language: '',
  description: '',
  serviceId: ''
})
const topicsInput = ref('')
const codeownersInput = ref('')
const saving = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  if (isEdit.value && id) {
    const repo = await repositoryApi.get(id)
    form.value = {
      orgRepo: repo.orgRepo,
      defaultBranch: repo.defaultBranch,
      language: repo.language ?? '',
      description: repo.description ?? '',
      serviceId: repo.serviceId ?? ''
    }
    topicsInput.value = repo.topics.join(', ')
    codeownersInput.value = repo.codeowners.join(', ')
  }
})

async function submit() {
  saving.value = true
  error.value = null
  try {
    const payload = {
      orgRepo: form.value.orgRepo,
      defaultBranch: form.value.defaultBranch || 'main',
      language: form.value.language || undefined,
      description: form.value.description || undefined,
      serviceId: form.value.serviceId || undefined,
      topics: topicsInput.value
        .split(',')
        .map(s => s.trim())
        .filter(Boolean),
      codeowners: codeownersInput.value
        .split(',')
        .map(s => s.trim())
        .filter(Boolean)
    }
    await repositoryApi.create(payload)
    router.push('/')
  } catch (e: unknown) {
    error.value = (isAxiosError(e) && e.response?.data?.message) || 'Failed to save repository'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.editor-page {
  max-width: 600px;
}
h1 {
  font-size: 1.5rem;
  margin-bottom: 1.5rem;
  color: #2d3748;
}
.editor-form {
  background: white;
  border-radius: 10px;
  padding: 1.5rem;
  border: 1px solid #e2e8f0;
}
.field {
  margin-bottom: 1rem;
}
label {
  display: block;
  font-size: 0.85rem;
  font-weight: 600;
  color: #4a5568;
  margin-bottom: 0.3rem;
}
input,
textarea {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #cbd5e0;
  border-radius: 6px;
  font-size: 0.9rem;
  outline: none;
}
input:focus,
textarea:focus {
  border-color: #63b3ed;
  box-shadow: 0 0 0 2px rgba(99, 179, 237, 0.3);
}
textarea {
  resize: vertical;
  font-family: inherit;
}
.actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
  align-items: center;
}
.btn-primary {
  background: #2b6cb0;
  color: white;
  border: none;
  padding: 0.6rem 1.25rem;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) {
  background: #2c5282;
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.btn-secondary {
  color: #718096;
  text-decoration: none;
  font-size: 0.9rem;
}
.error {
  color: #e53e3e;
  margin-top: 1rem;
  font-size: 0.85rem;
}
</style>
