<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  describeDuration,
  lifecycleApi,
  type ArchiveResult,
  type LifecycleStatus
} from '@/services/lifecycleApi'
import { useCanAdmin } from '@/auth/canAdmin'
import { formatInstant } from '@/lib/time'
import { refusalReason } from '@/auth/scopes'

/**
 * The data lifecycle at a glance (#33): which ontology version the graph is on beside the build's,
 * whether the archive is on and what it would take, how many earlier versions a node keeps, and each
 * connector's rules for what it stops reporting.
 *
 * Applying migrations and running the archive change the graph as a whole, so they are offered only
 * to an admin, and the archive's button only when an operator has turned the archive on. Its button
 * rehearses: what it would take is counted, and nothing changes.
 */
const status = ref<LifecycleStatus | null>(null)
const error = ref('')
const applying = ref(false)
const applyError = ref('')
const rehearsal = ref<ArchiveResult | null>(null)
const canAdmin = useCanAdmin()

type Refusal = { response?: { data?: { error?: string; detail?: string } } }

const load = async () => {
  error.value = ''
  try {
    status.value = await lifecycleApi.status()
  } catch {
    status.value = null
    error.value = 'The lifecycle status could not be loaded.'
  }
}

const apply = async () => {
  applying.value = true
  applyError.value = ''
  try {
    await lifecycleApi.applyMigrations()
    await load()
  } catch (caught) {
    const data = (caught as Refusal).response?.data
    applyError.value =
      refusalReason(data) ?? data?.detail ?? data?.error ?? 'The migrations could not be applied.'
  } finally {
    applying.value = false
  }
}

const rehearse = async () => {
  rehearsal.value = await lifecycleApi.archive({ dryRun: true })
}

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`

onMounted(load)
</script>

<template>
  <section class="lifecycle">
    <header>
      <h1>Data lifecycle</h1>
    </header>
    <p class="intro">
      How the graph keeps what changed, retires what its sources stop reporting, archives what has
      long since ended, and follows its ontology from one version to the next.
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <template v-if="status">
      <section class="panel" aria-labelledby="migrations-heading">
        <h2 id="migrations-heading">Ontology migrations</h2>
        <p data-test="migrations-status">
          <template v-if="status.migrations.upToDate">
            The graph is up to date with ontology {{ status.migrations.registryVersion }}.
          </template>
          <template v-else>
            The graph is on ontology {{ status.migrations.dbVersion ?? 'no recorded version' }};
            this build ships {{ status.migrations.registryVersion }}.
          </template>
          <span class="mode"
            >Migrations apply
            {{
              status.migrations.mode === 'auto' ? 'at startup' : 'when an admin applies them'
            }}.</span
          >
        </p>
        <ul v-if="status.migrations.pending.length" class="pending">
          <li v-for="migration in status.migrations.pending" :key="migration.version">
            <strong>{{ migration.version }}</strong> {{ migration.name }}
            <span v-if="migration.description" class="muted">— {{ migration.description }}</span>
          </li>
        </ul>
        <button
          v-if="canAdmin && !status.migrations.upToDate"
          type="button"
          data-test="migrations-apply"
          :disabled="applying"
          @click="apply"
        >
          Apply
        </button>
        <p v-if="applyError" class="error" role="alert">{{ applyError }}</p>
      </section>

      <section class="panel" aria-labelledby="archive-heading">
        <h2 id="archive-heading">Archive</h2>
        <p data-test="archive-status">
          <template v-if="status.archive.enabled">
            On, in {{ status.archive.mode }} mode, on the schedule
            <code>{{ status.archive.schedule }}</code
            >.
          </template>
          <template v-else>Archival is off: nothing is archived or deleted.</template>
          Closed facts older than {{ describeDuration(status.archive.retention) }} (before
          {{ formatInstant(status.archive.cutoff) }}):
          {{ plural(status.archive.eligible.nodes, 'node') }},
          {{ plural(status.archive.eligible.edges, 'relationship') }}.
        </p>
        <button
          v-if="canAdmin && status.archive.enabled"
          type="button"
          data-test="archive-run"
          @click="rehearse"
        >
          Rehearse the archive
        </button>
        <p v-if="rehearsal" data-test="archive-result">
          It would take {{ plural(rehearsal.wouldArchive?.nodes ?? 0, 'node') }} and
          {{ plural(rehearsal.wouldArchive?.edges ?? 0, 'relationship') }}; nothing was changed.
        </p>
      </section>

      <section class="panel" aria-labelledby="versioning-heading">
        <h2 id="versioning-heading">Versions</h2>
        <p data-test="versioning-status">
          <template v-if="status.versioning.enabled">
            A node keeps up to {{ status.versioning.maxVersions }} earlier sets of values.
          </template>
          <template v-else>Earlier values are not kept.</template>
          <span v-if="status.versioning.excludedTypes.length" class="muted">
            Not kept for {{ status.versioning.excludedTypes.join(', ') }}.
          </span>
        </p>
      </section>

      <section class="panel" aria-labelledby="connectors-heading">
        <h2 id="connectors-heading">What each connector stops reporting</h2>
        <table data-test="connector-rules">
          <thead>
            <tr>
              <th>Connector</th>
              <th>Source</th>
              <th>Missing from a full sync</th>
              <th>Grace period</th>
              <th>Full sync</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="connector in status.connectors" :key="connector.name">
              <td>{{ connector.name }}</td>
              <td>{{ connector.sourceSystem }}</td>
              <td>{{ connector.missingFromFullSync }}</td>
              <td>{{ describeDuration(connector.gracePeriod) }}</td>
              <td>{{ connector.fullSyncIsComplete ? 'complete' : 'scoped: retires nothing' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="muted">Only a full sync that succeeded ever retires anything.</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.lifecycle {
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
h2 {
  font-size: 1rem;
  margin-bottom: 0.5rem;
}
.intro,
.muted,
.mode {
  color: #4a5568;
}
.intro {
  margin-bottom: 1rem;
}
.panel {
  border-top: 1px solid #edf2f7;
  padding: 1rem 0;
}
.panel p {
  margin-bottom: 0.5rem;
}
.pending {
  margin: 0 0 0.75rem 1.25rem;
}
.error {
  color: #c53030;
}
button {
  padding: 0.35rem 0.9rem;
  border: 1px solid #2b6cb0;
  background: #2b6cb0;
  color: #fff;
  border-radius: 4px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
}
table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 0.5rem;
}
th,
td {
  text-align: left;
  padding: 0.5rem;
  border-bottom: 1px solid #edf2f7;
  font-size: 0.9rem;
}
th {
  color: #718096;
  font-size: 0.75rem;
  text-transform: uppercase;
}
</style>
