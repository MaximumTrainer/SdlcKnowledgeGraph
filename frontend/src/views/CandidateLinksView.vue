<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  linkApi,
  REVIEWABLE_STATUSES,
  type CandidateLink,
  type CandidatePage
} from '@/services/linkApi'
import { syncRunApi } from '@/services/syncRunApi'
import { useCanWrite } from '@/auth/canWrite'
import { refusalReason } from '@/auth/scopes'
import { formatInstant } from '@/lib/time'
import { nodeRoute } from '@/lib/nodeRoute'
import ConfidenceBar from '@/components/ConfidenceBar.vue'
import EvidenceList from '@/components/EvidenceList.vue'

/**
 * The review of the link engine's work (#28, FR12).
 *
 * Each candidate says which repository might own which cloud resource, on which rule, how sure the
 * engine is and on what evidence, so a reviewer can accept it - it becomes a stated owner - or reject
 * it, which the engine then keeps to. Deciding and starting a resolution change the graph, so they
 * are offered only to a user holding graph:write; anyone may read the list.
 */
const PAGE_SIZE = 50
const POLL_MS = 500
const PROVIDERS = ['aws', 'azure', 'gcp']

const canWrite = useCanWrite()

const filters = reactive({ status: '', provider: '', minConfidence: '', search: '' })
const pageNumber = ref(0)
const result = ref<CandidatePage | null>(null)
const loading = ref(true)
const error = ref('')
const expanded = ref<Set<string>>(new Set())
const deciding = ref<string | null>(null)
const resolution = ref<{ status: string } | null>(null)

type Refusal = { response?: { status?: number; data?: { error?: string; detail?: string } } }

/** Each load is numbered, and only the latest may write, so a slow answer to an old filter is dropped. */
let latestLoad = 0
let polling: ReturnType<typeof setTimeout> | null = null

const describe = (caught: unknown, fallback: string) => {
  const response = (caught as Refusal).response
  return (
    refusalReason(response?.data) ?? response?.data?.detail ?? response?.data?.error ?? fallback
  )
}

const load = async () => {
  const thisLoad = ++latestLoad
  loading.value = true
  try {
    const page = await linkApi.candidates({
      status: filters.status || undefined,
      provider: filters.provider || undefined,
      minConfidence: filters.minConfidence || undefined,
      q: filters.search.trim() || undefined,
      page: pageNumber.value,
      size: PAGE_SIZE
    })
    if (thisLoad !== latestLoad) return
    result.value = page
  } catch (caught) {
    if (thisLoad !== latestLoad) return
    result.value = null
    error.value = describe(caught, 'The link candidates could not be loaded.')
  } finally {
    if (thisLoad === latestLoad) loading.value = false
  }
}

const goTo = (page: number) => {
  pageNumber.value = page
  error.value = ''
  void load()
}

// A new filter starts from the first page: page 3 of the old results means nothing in the new ones.
watch(filters, () => goTo(0))

const toggle = (id: string) => {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

const decide = async (candidate: CandidateLink, decision: 'accept' | 'reject') => {
  error.value = ''
  deciding.value = candidate.id
  try {
    await (decision === 'accept' ? linkApi.accept(candidate.id) : linkApi.reject(candidate.id))
    await load()
  } catch (caught) {
    error.value = describe(caught, `The candidate could not be ${decision}ed.`)
  } finally {
    deciding.value = null
  }
}

const isOpen = (candidate: CandidateLink) =>
  candidate.status === 'pending' || candidate.status === 'conflict'

/** Asks the run how it is getting on until it has finished, then lists what it found. */
const follow = async (runId: string) => {
  try {
    const run = await syncRunApi.get(runId)
    resolution.value = { status: run.status ?? 'RUNNING' }
    if (run.status === 'RUNNING' || run.status === null) {
      polling = setTimeout(() => void follow(runId), POLL_MS)
      return
    }
    await load()
  } catch (caught) {
    resolution.value = null
    error.value = describe(caught, 'The resolution could not be followed.')
  }
}

const runResolution = async () => {
  error.value = ''
  try {
    const started = await linkApi.resolve()
    resolution.value = { status: 'RUNNING' }
    await follow(started.syncRunId)
  } catch (caught) {
    resolution.value = null
    error.value = describe(caught, 'The resolution could not be started.')
  }
}

onMounted(() => void load())
onBeforeUnmount(() => {
  if (polling) clearTimeout(polling)
})
</script>

<template>
  <section class="candidates">
    <header>
      <h1>Link candidates</h1>
      <button
        v-if="canWrite"
        type="button"
        data-test="run-resolution"
        :disabled="resolution?.status === 'RUNNING'"
        @click="runResolution"
      >
        Run resolution
      </button>
    </header>
    <p class="intro">
      Repositories the link engine proposes as owners of cloud resources, strongest first. Below the
      ownership threshold, or against a stronger owner, a link waits here for a person to accept or
      reject it.
    </p>
    <p v-if="resolution" data-test="resolution-status" aria-live="polite" class="resolution">
      Resolution {{ resolution.status }}
    </p>

    <form class="filters" aria-label="Filter link candidates" @submit.prevent>
      <div class="filter">
        <label for="filter-status">Status</label>
        <select id="filter-status" v-model="filters.status">
          <option value="">Open (pending and conflict)</option>
          <option v-for="status in REVIEWABLE_STATUSES" :key="status" :value="status">
            {{ status }}
          </option>
        </select>
      </div>
      <div class="filter">
        <label for="filter-provider">Provider</label>
        <select id="filter-provider" v-model="filters.provider">
          <option value="">Any provider</option>
          <option v-for="provider in PROVIDERS" :key="provider" :value="provider">
            {{ provider }}
          </option>
        </select>
      </div>
      <div class="filter">
        <label for="filter-min-confidence">Minimum confidence</label>
        <input
          id="filter-min-confidence"
          v-model="filters.minConfidence"
          type="number"
          min="0"
          max="1"
          step="0.05"
        />
      </div>
      <div class="filter">
        <label for="filter-search">Search</label>
        <input
          id="filter-search"
          v-model="filters.search"
          type="search"
          placeholder="Resource or repository"
        />
      </div>
    </form>

    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="loading && !result" aria-busy="true">Loading…</p>
    <p v-else-if="result && result.items.length === 0" data-test="no-candidates">
      No candidate links match. A resolution proposes them from tags, deployments, infrastructure as
      code and names.
    </p>

    <template v-if="result && result.items.length">
      <ul class="list" :aria-busy="loading ? 'true' : 'false'">
        <li v-for="candidate in result.items" :key="candidate.id" data-test="candidate">
          <div class="row">
            <div class="ends">
              <div class="resource">
                <span class="provider">{{ candidate.resource.provider ?? '?' }}</span>
                <router-link
                  :to="nodeRoute('CloudResource', candidate.resource.key)"
                  :title="candidate.resource.key"
                >
                  {{ candidate.resource.name ?? candidate.resource.key }}
                </router-link>
              </div>
              <span class="arrow" aria-hidden="true">→</span>
              <router-link :to="nodeRoute('Repository', candidate.repository.key)">
                {{ candidate.repository.key }}
              </router-link>
            </div>
            <span class="rule" data-test="rule-badge">{{ candidate.rule }}</span>
            <ConfidenceBar :value="candidate.confidence" />
            <span class="status" :class="candidate.status" data-test="candidate-status">{{
              candidate.status
            }}</span>
            <div class="actions">
              <button
                type="button"
                data-test="evidence-toggle"
                :aria-expanded="expanded.has(candidate.id) ? 'true' : 'false'"
                @click="toggle(candidate.id)"
              >
                Evidence
              </button>
              <template v-if="canWrite && isOpen(candidate)">
                <button
                  type="button"
                  data-test="accept"
                  class="accept"
                  :disabled="deciding === candidate.id"
                  @click="decide(candidate, 'accept')"
                >
                  Accept
                </button>
                <button
                  type="button"
                  data-test="reject"
                  class="reject"
                  :disabled="deciding === candidate.id"
                  @click="decide(candidate, 'reject')"
                >
                  Reject
                </button>
              </template>
            </div>
          </div>
          <p v-if="candidate.rejectedBy" class="decided">
            Rejected by {{ candidate.rejectedBy }}
            <time v-if="candidate.rejectedAt" :datetime="candidate.rejectedAt">
              {{ formatInstant(candidate.rejectedAt) }}
            </time>
          </p>
          <EvidenceList v-if="expanded.has(candidate.id)" :evidence="candidate.evidence" />
        </li>
      </ul>

      <nav class="pager" aria-label="Candidate pages">
        <button type="button" :disabled="pageNumber === 0 || loading" @click="goTo(pageNumber - 1)">
          Previous
        </button>
        <span aria-live="polite">
          Page {{ result.page + 1 }} of {{ Math.max(result.totalPages, 1) }} ·
          {{ result.totalElements }}
          {{ result.totalElements === 1 ? 'candidate' : 'candidates' }}
        </span>
        <button
          type="button"
          :disabled="result.page + 1 >= result.totalPages || loading"
          @click="goTo(pageNumber + 1)"
        >
          Next
        </button>
      </nav>
    </template>
  </section>
</template>

<style scoped>
.candidates {
  background: #fff;
  border-radius: 6px;
  padding: 1.5rem;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}
h1 {
  font-size: 1.25rem;
}
.intro {
  color: #4a5568;
  margin-bottom: 1rem;
}
.resolution {
  font-size: 0.85rem;
  color: #2b6cb0;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1rem;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}
.filter {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.filters label {
  color: #718096;
}
.filters select,
.filters input {
  padding: 0.3rem;
  border: 1px solid #cbd5e0;
  border-radius: 4px;
}
.list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.list > li {
  border-bottom: 1px solid #edf2f7;
  padding: 0.6rem 0;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto auto auto;
  gap: 0.75rem;
  align-items: center;
  font-size: 0.9rem;
}
.ends {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;
  min-width: 0;
  word-break: break-all;
}
.provider {
  font-size: 0.7rem;
  text-transform: uppercase;
  color: #718096;
  margin-right: 0.3rem;
}
.arrow {
  color: #a0aec0;
}
.rule {
  font-size: 0.75rem;
  background: #ebf8ff;
  color: #2b6cb0;
  border-radius: 999px;
  padding: 0.1rem 0.5rem;
}
.status {
  font-size: 0.75rem;
  color: #4a5568;
}
.status.conflict {
  color: #c05621;
}
.status.rejected {
  color: #822727;
}
.actions {
  display: flex;
  gap: 0.4rem;
}
button {
  border: 1px solid #cbd5e0;
  background: #fff;
  border-radius: 4px;
  padding: 0.25rem 0.7rem;
  cursor: pointer;
  font-size: 0.8rem;
}
.accept {
  border-color: #2f855a;
  color: #2f855a;
}
.reject {
  border-color: #c53030;
  color: #c53030;
}
.decided {
  font-size: 0.8rem;
  color: #718096;
  margin: 0;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 1rem;
  margin-top: 1rem;
  font-size: 0.85rem;
  color: #4a5568;
}
.error {
  color: #c53030;
}
</style>
