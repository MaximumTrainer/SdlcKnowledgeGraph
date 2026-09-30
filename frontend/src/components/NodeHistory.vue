<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { lifecycleApi, type NodeHistory } from '@/services/lifecycleApi'
import { formatInstant } from '@/lib/time'

/**
 * A node's history on its page (#33): the values it holds now, then each set it held before, newest
 * first, with when each held and, for a retirement, why it ended. Each entry lists only the values
 * that differ from the set beside it, one line each as `name: value`; a list or an object is shown as
 * JSON.
 */
const props = defineProps<{ nodeId: string }>()

const history = ref<NodeHistory | null>(null)
const failed = ref(false)

const load = async () => {
  failed.value = false
  try {
    history.value = await lifecycleApi.history(props.nodeId)
  } catch {
    history.value = null
    failed.value = true
  }
}

const show = (value: unknown): string =>
  value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value)

/**
 * What [values] held that [other] - the set of values beside it in time - did not: each entry says
 * what changed, not everything, so the page above is not repeated and a long node stays short.
 */
const changed = (values: Record<string, unknown>, other: Record<string, unknown> | undefined) =>
  other === undefined
    ? []
    : Object.entries(values)
        .filter(([name, value]) => JSON.stringify(value) !== JSON.stringify(other[name]))
        .sort(([left], [right]) => left.localeCompare(right))

const newerThan = (index: number) =>
  index === 0 ? history.value?.current.props : history.value?.versions[index - 1]?.props

onMounted(load)
watch(() => props.nodeId, load)
</script>

<template>
  <section class="node-history" data-test="node-history" aria-labelledby="history-heading">
    <h2 id="history-heading">History</h2>
    <p v-if="failed" class="muted">The history could not be loaded.</p>
    <ol v-if="history">
      <li data-test="history-entry" class="entry">
        <p class="when">
          <strong>current</strong> since {{ formatInstant(history.current.propsFrom) }}
          <template v-if="history.current.retired">
            — retired {{ formatInstant(history.current.validTo) }}
            <span v-if="history.current.retiredReason">({{ history.current.retiredReason }})</span>
          </template>
          <template v-if="history.current.resurrectedAt">
            — back since {{ formatInstant(history.current.resurrectedAt) }}
          </template>
        </p>
        <ul class="values">
          <li
            v-for="[name, value] in changed(history.current.props, history.versions[0]?.props)"
            :key="name"
          >
            {{ `${name}: ${show(value)}` }}
          </li>
        </ul>
      </li>
      <li
        v-for="(version, index) in history.versions"
        :key="version.validFrom"
        data-test="history-entry"
        class="entry earlier"
      >
        <p class="when">
          {{ formatInstant(version.validFrom) }} to {{ formatInstant(version.validTo) }}
          <template v-if="version.retired">
            — retired<span v-if="version.retiredReason"> ({{ version.retiredReason }})</span>
          </template>
          <span class="muted">from {{ version.provenance.sourceSystem }}</span>
        </p>
        <ul class="values">
          <li v-for="[name, value] in changed(version.props, newerThan(index))" :key="name">
            {{ `${name}: ${show(value)}` }}
          </li>
        </ul>
      </li>
    </ol>
    <p v-if="history && !history.versions.length" class="muted" data-test="history-empty">
      It has no earlier versions: its values have not changed since it began.
    </p>
  </section>
</template>

<style scoped>
.node-history {
  margin-top: 1.5rem;
}
h2 {
  font-size: 1rem;
  margin-bottom: 0.5rem;
}
ol {
  list-style: none;
}
.entry {
  border-left: 3px solid #2b6cb0;
  padding: 0.25rem 0 0.25rem 0.75rem;
  margin-bottom: 0.75rem;
}
.earlier {
  border-left-color: #cbd5e0;
}
.when {
  font-size: 0.85rem;
  margin-bottom: 0.25rem;
}
.muted {
  color: #718096;
}
.values {
  list-style: none;
  font-size: 0.85rem;
  word-break: break-word;
}
</style>
