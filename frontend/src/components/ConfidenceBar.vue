<script setup lang="ts">
import { computed } from 'vue'

/**
 * How sure the link engine is, at a glance (#28): a bar as long as the confidence, the percentage
 * beside it, and the value as a meter for a screen reader. Below the threshold - a candidate only -
 * it is drawn weak; at or above, where it would own the resource, strong.
 */
const props = withDefaults(defineProps<{ value: number; threshold?: number }>(), {
  threshold: 0.5
})

const percent = computed(() => {
  const value = Number.isFinite(props.value) ? props.value : 0
  return Math.round(Math.min(1, Math.max(0, value)) * 100)
})
const strength = computed(() => (props.value >= props.threshold ? 'strong' : 'weak'))
</script>

<template>
  <span
    data-test="confidence-bar"
    role="meter"
    :aria-valuenow="percent"
    aria-valuemin="0"
    aria-valuemax="100"
    :aria-label="`Confidence ${percent}%`"
    class="confidence"
    :class="strength"
  >
    <span class="track" aria-hidden="true">
      <span data-test="confidence-fill" class="fill" :style="{ width: `${percent}%` }"></span>
    </span>
    <span class="value">{{ percent }}%</span>
  </span>
</template>

<style scoped>
.confidence {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.8rem;
}
.track {
  display: inline-block;
  width: 5rem;
  height: 0.5rem;
  background: #edf2f7;
  border-radius: 999px;
  overflow: hidden;
}
.fill {
  display: block;
  height: 100%;
}
.weak .fill {
  background: #d69e2e;
}
.strong .fill {
  background: #2f855a;
}
.value {
  color: #4a5568;
  min-width: 2.5rem;
}
</style>
