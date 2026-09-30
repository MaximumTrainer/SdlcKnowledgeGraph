<script setup lang="ts">
import { colourFor } from '@/lib/ontologyColours'

/**
 * What the drawing means (#9, FR5): a colour per node type on the canvas, and the difference between
 * an edge a system reported and one a rule inferred - which is the difference a reader most needs
 * before trusting a path.
 */
defineProps<{ types: string[] }>()
</script>

<template>
  <div class="graph-legend" data-test="graph-legend" aria-label="Legend">
    <span class="entry">
      <svg width="28" height="8" aria-hidden="true">
        <line x1="0" y1="4" x2="28" y2="4" stroke="#718096" stroke-width="2" />
      </svg>
      solid: reported by a system of record
    </span>
    <span class="entry">
      <svg width="28" height="8" aria-hidden="true">
        <line
          x1="0"
          y1="4"
          x2="28"
          y2="4"
          stroke="#718096"
          stroke-width="2"
          stroke-dasharray="6 4"
          opacity="0.6"
        />
      </svg>
      dashed: inferred by a rule, fainter the less confident
    </span>
    <span v-for="type in types" :key="type" class="entry">
      <span class="swatch" :style="{ background: colourFor(type) }" aria-hidden="true" />
      {{ type }}
    </span>
  </div>
</template>

<style scoped>
.graph-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem 1rem;
  font-size: 0.75rem;
  color: #4a5568;
  margin-top: 0.5rem;
}
.entry {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}
.swatch {
  display: inline-block;
  width: 0.7rem;
  height: 0.7rem;
  border-radius: 50%;
}
</style>
