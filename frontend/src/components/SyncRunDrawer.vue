<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { syncRunApi, type SyncRunDetail } from '@/services/syncRunApi'
import { connectorApi } from '@/services/connectorApi'
import { formatDuration, formatInstant } from '@/lib/time'
import StatusChip from './StatusChip.vue'

/**
 * One run in full, and the way to run its connector again (#29, FR8).
 *
 * A re-run is a write. A read-only instance refuses every write (docs/USER-GUIDE.md), so on one the
 * button is disabled with the reason beside it rather than offered and then refused. The flag comes
 * from `/actuator/info`; if it could not be read the button is offered, and a refusal shows the API's
 * own words.
 */
const props = defineProps<{ runId: string; readOnly: boolean }>()
const emit = defineEmits<{ close: []; started: [runId: string] }>()

type Refusal = { response?: { status?: number; data?: { error?: string; detail?: string } } }

const run = ref<SyncRunDetail | null>(null)
const loadError = ref('')
const rerunError = ref('')
const startedRunId = ref<string | null>(null)
const starting = ref(false)
const closeButton = ref<HTMLButtonElement | null>(null)

const headingId = computed(() => `sync-run-${props.runId}-heading`)
const readOnlyNoteId = computed(() => `sync-run-${props.runId}-read-only`)

/** Only full and incremental can be asked for; a webhook run is replayed as an incremental sync. */
const rerunMode = computed<'full' | 'incremental'>(() =>
  run.value?.mode === 'FULL' ? 'full' : 'incremental'
)

const detailsJson = computed(() => JSON.stringify(run.value?.details ?? {}, null, 2))

const load = async () => {
  run.value = null
  loadError.value = ''
  rerunError.value = ''
  startedRunId.value = null
  try {
    run.value = await syncRunApi.get(props.runId)
  } catch (caught) {
    loadError.value =
      (caught as Refusal).response?.status === 404
        ? 'This run is no longer recorded. Runs are pruned after the retention period.'
        : 'The run could not be loaded.'
  }
}

const rerun = async () => {
  if (!run.value) return
  const connector = run.value.connector
  starting.value = true
  rerunError.value = ''
  startedRunId.value = null
  try {
    const { syncRunId } = await connectorApi.sync(connector, rerunMode.value)
    startedRunId.value = syncRunId
    emit('started', syncRunId)
  } catch (caught) {
    const response = (caught as Refusal).response
    rerunError.value =
      response?.status === 409
        ? `${connector} is already syncing. Try again when that run finishes.`
        : (response?.data?.detail ??
          response?.data?.error ??
          `${connector} could not be asked to sync.`)
  } finally {
    starting.value = false
  }
}

watch(() => props.runId, load)

onMounted(async () => {
  await nextTick()
  closeButton.value?.focus()
  await load()
})
</script>

<template>
  <div class="backdrop" @click.self="emit('close')">
    <aside
      role="dialog"
      aria-modal="true"
      :aria-labelledby="headingId"
      class="drawer"
      @keydown.esc="emit('close')"
    >
      <header>
        <h2 :id="headingId">Sync run</h2>
        <button
          ref="closeButton"
          type="button"
          class="close"
          data-test="close-drawer"
          aria-label="Close"
          @click="emit('close')"
        >
          ×
        </button>
      </header>

      <p class="run-id">{{ runId }}</p>

      <p v-if="loadError" role="alert" class="error">{{ loadError }}</p>
      <p v-else-if="!run" aria-busy="true">Loading…</p>

      <template v-else>
        <dl>
          <dt>Connector</dt>
          <dd>{{ run.connector }}</dd>
          <dt>Mode</dt>
          <dd>{{ run.mode ?? '—' }}</dd>
          <dt>Status</dt>
          <dd><StatusChip :status="run.status" /></dd>
          <dt>Started</dt>
          <dd>{{ formatInstant(run.startedAt) || '—' }}</dd>
          <dt>Finished</dt>
          <dd>{{ formatInstant(run.finishedAt) || '—' }}</dd>
          <dt>Duration</dt>
          <dd>{{ formatDuration(run.durationMs) }}</dd>
          <dt>Nodes / edges / tombstones</dt>
          <dd>{{ run.nodesUpserted }} / {{ run.edgesUpserted }} / {{ run.tombstones }}</dd>
          <dt>Watermark</dt>
          <dd>{{ formatInstant(run.watermark) || '—' }}</dd>
          <template v-if="run.sourceId">
            <dt>Delivery</dt>
            <dd>{{ run.sourceId }}</dd>
          </template>
        </dl>

        <template v-if="run.error">
          <h3>Error</h3>
          <pre data-test="run-error" class="run-error">{{ run.error }}</pre>
        </template>

        <h3>Details</h3>
        <pre data-test="run-details">{{ detailsJson }}</pre>

        <div class="actions">
          <button
            type="button"
            data-test="rerun"
            :disabled="readOnly || starting"
            :aria-describedby="readOnly ? readOnlyNoteId : undefined"
            @click="rerun"
          >
            {{ starting ? 'Starting…' : 'Re-run' }}
          </button>
          <small v-if="readOnly" :id="readOnlyNoteId">
            This instance is read-only, so it cannot start runs.
          </small>
          <small v-else>As {{ rerunMode === 'full' ? 'a full' : 'an incremental' }} sync.</small>
        </div>
        <p
          v-if="startedRunId"
          data-test="rerun-result"
          :data-run-id="startedRunId"
          role="status"
          class="started"
        >
          Started run {{ startedRunId }} for {{ run.connector }}.
        </p>
        <p v-if="rerunError" role="alert" class="error">{{ rerunError }}</p>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  background: rgb(26 32 44 / 40%);
  display: flex;
  justify-content: flex-end;
  z-index: 10;
}
.drawer {
  background: #fff;
  width: min(560px, 100%);
  height: 100%;
  overflow-y: auto;
  padding: 1.5rem;
  box-shadow: -4px 0 12px rgb(0 0 0 / 15%);
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
h2 {
  font-size: 1.15rem;
}
h3 {
  font-size: 0.8rem;
  color: #718096;
  text-transform: uppercase;
  margin: 1.25rem 0 0.4rem;
}
.close {
  background: none;
  border: none;
  font-size: 1.5rem;
  line-height: 1;
  cursor: pointer;
  color: #4a5568;
}
.run-id {
  font-family: ui-monospace, monospace;
  font-size: 0.8rem;
  color: #718096;
  margin: 0.25rem 0 1rem;
  word-break: break-all;
}
dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.35rem 1rem;
  font-size: 0.9rem;
}
dt {
  color: #718096;
}
pre {
  background: #f7fafc;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  padding: 0.75rem;
  font-size: 0.8rem;
  white-space: pre-wrap;
  word-break: break-word;
}
.run-error {
  border-color: #feb2b2;
  background: #fff5f5;
  color: #822727;
}
.actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-top: 1.25rem;
}
.actions button {
  background: #2b6cb0;
  color: #fff;
  border: none;
  border-radius: 4px;
  padding: 0.4rem 1rem;
  cursor: pointer;
}
.actions button:disabled {
  background: #a0aec0;
  cursor: not-allowed;
}
small {
  color: #718096;
}
.started {
  color: #2b6cb0;
  margin-top: 0.75rem;
}
.error {
  color: #c53030;
  margin-top: 0.75rem;
}
</style>
