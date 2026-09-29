<script setup lang="ts">
import { computed } from 'vue'

/**
 * A run's status, read at a glance down a column. The word is always there, so the colour is never
 * the only thing carrying it.
 */
const props = defineProps<{ status: string | null }>()

const label = computed(() => props.status ?? 'UNKNOWN')
</script>

<template>
  <span data-test="status-chip" class="chip" :class="label.toLowerCase()">{{ label }}</span>
</template>

<style scoped>
.chip {
  display: inline-block;
  border-radius: 999px;
  padding: 0.1rem 0.55rem;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  background: #e2e8f0;
  color: #4a5568;
}
.success {
  background: #c6f6d5;
  color: #22543d;
}
.partial {
  background: #feebc8;
  color: #7b341e;
}
.failed {
  background: #fed7d7;
  color: #822727;
}
.running {
  background: #bee3f8;
  color: #2a4365;
}
</style>
