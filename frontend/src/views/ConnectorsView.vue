<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { connectorApi, type ConnectorSummary } from '@/services/connectorApi'

/**
 * What is ingesting, and whether it is working.
 *
 * The screen exists to answer "why does the graph look empty" without reading logs. A connector that
 * is switched off, or whose source system is unreachable, says so here — those are the two causes,
 * and both are invisible everywhere else (#22).
 */
const connectors = ref<ConnectorSummary[]>([])
const error = ref('')
const syncResult = ref('')
const syncing = ref<string | null>(null)

const load = async () => {
  try {
    connectors.value = await connectorApi.list()
  } catch {
    error.value = 'The connectors could not be loaded.'
  }
}

/**
 * A sync is accepted, not completed. The screen reports the run that was started rather than
 * pretending to know the outcome, because the outcome arrives minutes later.
 */
const sync = async (name: string) => {
  syncing.value = name
  syncResult.value = ''
  try {
    const { syncRunId } = await connectorApi.sync(name, 'incremental')
    syncResult.value = `Started run ${syncRunId} for ${name}.`
    await load()
  } catch {
    syncResult.value = `${name} refused the sync. A run may already be in progress.`
  } finally {
    syncing.value = null
  }
}

onMounted(load)
</script>

<template>
  <section class="connectors">
    <h1>Connectors</h1>
    <p class="intro">
      Where the graph gets its facts. A connector that is disabled makes no requests to anything.
    </p>

    <p v-if="error" class="error" data-test="connectors-error">{{ error }}</p>
    <p v-if="syncResult" class="sync-result" data-test="sync-result">{{ syncResult }}</p>

    <table v-if="connectors.length > 0">
      <thead>
        <tr>
          <th>Connector</th>
          <th>Source system</th>
          <th>State</th>
          <th>Health</th>
          <th>Last run</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="connector in connectors" :key="connector.name">
          <td>{{ connector.name }}</td>
          <td>{{ connector.sourceSystem }}</td>
          <td>
            <span
              :data-test="`enabled-${connector.name}`"
              :class="connector.enabled ? 'badge on' : 'badge off'"
              >{{ connector.enabled ? 'enabled' : 'disabled' }}</span
            >
          </td>
          <td :data-test="`health-${connector.name}`">
            {{ connector.health.status }}
            <small v-if="connector.health.detail">{{ connector.health.detail }}</small>
          </td>
          <td :data-test="`last-run-${connector.name}`">
            <template v-if="connector.lastRun?.status">
              {{ connector.lastRun.status }}
              <small v-if="connector.lastRun.finishedAt">{{ connector.lastRun.finishedAt }}</small>
            </template>
            <template v-else>never</template>
          </td>
          <td>
            <!-- Offered only when it would do something: a disabled connector is never scheduled. -->
            <button
              v-if="connector.enabled"
              type="button"
              :data-test="`sync-${connector.name}`"
              :disabled="syncing === connector.name"
              @click="sync(connector.name)"
            >
              {{ syncing === connector.name ? 'Starting…' : 'Sync now' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-else-if="!error" data-test="no-connectors">
      No connectors are registered. Nothing is ingesting into this graph.
    </p>
  </section>
</template>

<style scoped>
.connectors {
  padding: 1rem;
}

.intro {
  color: #4a5568;
}

table {
  width: 100%;
  border-collapse: collapse;
}

th,
td {
  text-align: left;
  padding: 0.5rem;
  border-bottom: 1px solid #e2e8f0;
  vertical-align: top;
}

small {
  display: block;
  color: #718096;
}

.badge {
  border-radius: 0.25rem;
  padding: 0.125rem 0.5rem;
  font-size: 0.875rem;
}

.badge.on {
  background: #c6f6d5;
  color: #22543d;
}

.badge.off {
  background: #e2e8f0;
  color: #4a5568;
}

.error {
  color: #c53030;
}

.sync-result {
  color: #2b6cb0;
}
</style>
