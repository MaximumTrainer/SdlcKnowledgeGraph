<script setup lang="ts">
import { computed } from 'vue'

/** What a link rests on (#28), each piece by name and in name order, for a reviewer to check. */
const props = defineProps<{ evidence: Record<string, string> }>()

const entries = computed(() =>
  Object.entries(props.evidence).sort(([a], [b]) => a.localeCompare(b))
)
</script>

<template>
  <div data-test="evidence-list" class="evidence">
    <dl v-if="entries.length">
      <template v-for="[name, value] in entries" :key="name">
        <dt>{{ name }}</dt>
        <dd>{{ value }}</dd>
      </template>
    </dl>
    <p v-else>No evidence recorded.</p>
  </div>
</template>

<style scoped>
.evidence {
  font-size: 0.8rem;
  background: #f7fafc;
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
}
dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.2rem 0.75rem;
  margin: 0;
}
dt {
  color: #718096;
}
dd {
  margin: 0;
  word-break: break-all;
  font-family: ui-monospace, monospace;
}
p {
  margin: 0;
  color: #718096;
}
</style>
