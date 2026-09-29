<script setup lang="ts">
import { computed } from 'vue'
import type { FreshnessView } from '@/services/connectorApi'
import { formatAge, formatSeconds } from '@/lib/time'

/**
 * How long since a connector last succeeded, and whether that is too long (#29, FR3).
 *
 * A stale connector is flagged with the word as well as the colour: an answer built on it is worse
 * than no answer, and that must not depend on telling red from grey.
 */
const props = defineProps<{ name: string; freshness: FreshnessView }>()

const title = computed(() => `Stale after ${formatSeconds(props.freshness.thresholdSeconds)}`)
</script>

<template>
  <span class="freshness" :title="title">
    <span class="age">{{ formatAge(freshness.ageSeconds) }}</span>
    <span v-if="freshness.stale" :data-test="`stale-${name}`" class="stale">stale</span>
  </span>
</template>

<style scoped>
.freshness {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}
.stale {
  border-radius: 0.25rem;
  padding: 0.05rem 0.4rem;
  font-size: 0.75rem;
  font-weight: 600;
  background: #fed7d7;
  color: #822727;
}
</style>
