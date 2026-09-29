<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { infoApi } from '@/services/api'
import { connectorApi } from '@/services/connectorApi'
import {
  RUN_STATUSES,
  syncRunApi,
  type SyncRunPage,
  type SyncRunSummary
} from '@/services/syncRunApi'
import { formatDuration, formatInstant, toInstant } from '@/lib/time'
import StatusChip from '@/components/StatusChip.vue'
import SyncRunDrawer from '@/components/SyncRunDrawer.vue'

/**
 * What the connectors did, newest first (#29, FR8).
 *
 * The page answers "which run went wrong, and why" without reading logs: each row says how the run
 * ended, how much it wrote and how long it took, and opens the whole run - its full error and what
 * the connector reported - in a drawer, from where it can be run again.
 */
const PAGE_SIZE = 20

const filters = reactive({ connector: '', status: '', from: '', to: '' })
const pageNumber = ref(0)
const result = ref<SyncRunPage | null>(null)
const loading = ref(true)
const error = ref('')
const connectorNames = ref<string[]>([])
const readOnly = ref(false)
const openRunId = ref<string | null>(null)

/** The element that opened the drawer, so focus goes back to it when the drawer closes. */
let opener: HTMLElement | null = null

type Refusal = { response?: { status?: number; data?: { error?: string; detail?: string } } }

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    result.value = await syncRunApi.list({
      connector: filters.connector || undefined,
      status: filters.status || undefined,
      from: toInstant(filters.from),
      to: toInstant(filters.to),
      page: pageNumber.value,
      size: PAGE_SIZE
    })
  } catch (caught) {
    const response = (caught as Refusal).response
    result.value = null
    error.value =
      response?.status === 400
        ? (response.data?.detail ?? response.data?.error ?? 'That filter was refused.')
        : 'The sync run history could not be loaded.'
  } finally {
    loading.value = false
  }
}

const goTo = (page: number) => {
  pageNumber.value = page
  void load()
}

// A new filter starts from the first page: page 3 of the old results means nothing in the new ones.
watch(filters, () => goTo(0))

const open = (run: SyncRunSummary, event: Event) => {
  opener = (event.currentTarget as HTMLElement | null) ?? null
  openRunId.value = run.id
}

const close = async () => {
  openRunId.value = null
  await nextTick()
  opener?.focus()
}

/** A new run is the newest, so it is at the top of the first page when it matches the filters. */
const onStarted = () => goTo(0)

onMounted(() => {
  void load()
  // Both only refine the page: without the connector list the filter offers "All connectors", and
  // without the deployment info a re-run is offered and a read-only refusal is shown when it comes.
  connectorApi
    .list()
    .then(connectors => {
      connectorNames.value = connectors.map(connector => connector.name)
    })
    .catch(() => {
      connectorNames.value = []
    })
  infoApi
    .get()
    .then(info => {
      readOnly.value = info.deployment.readOnly
    })
    .catch(() => {
      readOnly.value = false
    })
})
</script>

<template>
  <section class="sync-runs">
    <header>
      <h1>Sync runs</h1>
    </header>
    <p class="intro">
      Every connector run, newest first. Open a run for its whole error and what the connector
      reported.
    </p>

    <form class="filters" aria-label="Filter sync runs" @submit.prevent>
      <div class="filter">
        <label for="filter-connector">Connector</label>
        <select id="filter-connector" v-model="filters.connector">
          <option value="">All connectors</option>
          <option v-for="name in connectorNames" :key="name" :value="name">{{ name }}</option>
        </select>
      </div>
      <div class="filter">
        <label for="filter-status">Status</label>
        <select id="filter-status" v-model="filters.status">
          <option value="">Any status</option>
          <option v-for="status in RUN_STATUSES" :key="status" :value="status">
            {{ status }}
          </option>
        </select>
      </div>
      <div class="filter">
        <label for="filter-from">Started from (UTC)</label>
        <input id="filter-from" v-model="filters.from" type="datetime-local" />
      </div>
      <div class="filter">
        <label for="filter-to">Started before (UTC)</label>
        <input id="filter-to" v-model="filters.to" type="datetime-local" />
      </div>
    </form>

    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-else-if="loading && !result" aria-busy="true">Loading…</p>
    <p v-else-if="result && result.items.length === 0" data-test="no-runs">
      No sync runs match. A run is recorded each time a connector syncs.
    </p>

    <template v-else-if="result">
      <table :aria-busy="loading ? 'true' : 'false'">
        <thead>
          <tr>
            <th scope="col">Connector</th>
            <th scope="col">Mode</th>
            <th scope="col">Status</th>
            <th scope="col">Started</th>
            <th scope="col">Duration</th>
            <th scope="col"><abbr title="Nodes / edges / tombstones">N / E / T</abbr></th>
            <th scope="col">Error</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="run in result.items"
            :key="run.id"
            :data-test="`run-${run.id}`"
            class="run"
            @click="open(run, $event)"
          >
            <td>
              <!-- The row is the click target; the button is the same thing for a keyboard. -->
              <button
                type="button"
                class="open"
                :aria-label="`Open the ${run.connector} run started ${formatInstant(run.startedAt)}`"
                @click.stop="open(run, $event)"
              >
                {{ run.connector }}
              </button>
            </td>
            <td>{{ run.mode ?? '—' }}</td>
            <td><StatusChip :status="run.status" /></td>
            <td>
              <time v-if="run.startedAt" :datetime="run.startedAt">
                {{ formatInstant(run.startedAt) }}
              </time>
            </td>
            <td>{{ formatDuration(run.durationMs) }}</td>
            <td data-test="counts">
              {{ run.nodesUpserted }} / {{ run.edgesUpserted }} / {{ run.tombstones }}
            </td>
            <td class="error-snippet" :title="run.error ?? undefined">{{ run.error ?? '' }}</td>
          </tr>
        </tbody>
      </table>

      <nav class="pager" aria-label="Sync run pages">
        <button
          type="button"
          data-test="previous-page"
          :disabled="pageNumber === 0 || loading"
          @click="goTo(pageNumber - 1)"
        >
          Previous
        </button>
        <span data-test="page-status" aria-live="polite">
          Page {{ result.page + 1 }} of {{ Math.max(result.totalPages, 1) }} ·
          {{ result.totalElements }} {{ result.totalElements === 1 ? 'run' : 'runs' }}
        </span>
        <button
          type="button"
          data-test="next-page"
          :disabled="result.page + 1 >= result.totalPages || loading"
          @click="goTo(pageNumber + 1)"
        >
          Next
        </button>
      </nav>
    </template>

    <SyncRunDrawer
      v-if="openRunId"
      :run-id="openRunId"
      :read-only="readOnly"
      @close="close"
      @started="onStarted"
    />
  </section>
</template>

<style scoped>
.sync-runs {
  background: #fff;
  border-radius: 6px;
  padding: 1.5rem;
}
header {
  margin-bottom: 0.5rem;
}
h1 {
  font-size: 1.25rem;
}
.intro {
  color: #4a5568;
  margin-bottom: 1rem;
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
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  text-align: left;
  padding: 0.5rem;
  border-bottom: 1px solid #edf2f7;
  font-size: 0.9rem;
  vertical-align: top;
}
th {
  color: #718096;
  font-size: 0.75rem;
  text-transform: uppercase;
}
.run {
  cursor: pointer;
}
.run:hover {
  background: #f7fafc;
}
.open {
  background: none;
  border: none;
  padding: 0;
  color: #2b6cb0;
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
}
.error-snippet {
  color: #822727;
  max-width: 18rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
