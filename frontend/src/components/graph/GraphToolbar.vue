<script setup lang="ts">
import type { GraphDirection } from '@/services/api'
import type { Depth, GraphFilters } from '@/lib/graphQuery'
import { toggleType } from '@/lib/graphQuery'

/**
 * The graph view's controls (#9, FR6 and FR8): depth, which node and edge types to show, which way
 * to walk, fitting the drawing to the canvas, and the blast radius overlay.
 *
 * The type lists come from the ontology, so a type added to the registry is offered without a
 * frontend release. An empty selection means every type on offer, so every box starts ticked.
 */
const props = defineProps<{
  filters: GraphFilters
  nodeTypes: string[]
  edgeTypes: string[]
  blast: boolean
  blastOf: string | null
}>()

const emit = defineEmits<{
  'update:filters': [filters: GraphFilters]
  'update:blast': [on: boolean]
  fit: []
}>()

const ticked = (selected: string[], type: string) =>
  selected.length === 0 || selected.includes(type)

const setNodeType = (type: string, on: boolean) =>
  emit('update:filters', {
    ...props.filters,
    nodeTypes: toggleType(props.filters.nodeTypes, props.nodeTypes, type, on)
  })

const setEdgeType = (type: string, on: boolean) =>
  emit('update:filters', {
    ...props.filters,
    edgeTypes: toggleType(props.filters.edgeTypes, props.edgeTypes, type, on)
  })

// Committed on change rather than on every input, so dragging the slider fetches once.
const setDepth = (event: Event) => {
  const depth = Number((event.target as HTMLInputElement).value) as Depth
  if (depth !== props.filters.depth) emit('update:filters', { ...props.filters, depth })
}

const setDirection = (event: Event) =>
  emit('update:filters', {
    ...props.filters,
    direction: (event.target as HTMLSelectElement).value as GraphDirection
  })

const checked = (event: Event) => (event.target as HTMLInputElement).checked
</script>

<template>
  <div class="graph-toolbar" role="toolbar" aria-label="Graph controls">
    <label class="control">
      <span>Depth</span>
      <input
        type="range"
        min="1"
        max="3"
        step="1"
        aria-label="Depth"
        data-test="depth"
        :value="filters.depth"
        @change="setDepth"
      />
      <output>{{ filters.depth }}</output>
    </label>

    <label class="control">
      <span>Direction</span>
      <select data-test="direction" :value="filters.direction" @change="setDirection">
        <option value="both">both</option>
        <option value="out">out</option>
        <option value="in">in</option>
      </select>
    </label>

    <button type="button" data-test="fit" @click="emit('fit')">Fit</button>

    <label class="control blast">
      <input
        type="checkbox"
        data-test="blast-radius"
        :checked="blast"
        @change="emit('update:blast', checked($event))"
      />
      <span>Blast radius</span>
      <small v-if="blastOf" class="blast-of">of {{ blastOf }}</small>
    </label>

    <fieldset data-test="node-type-filter" class="types">
      <legend>Node types</legend>
      <label v-for="type in nodeTypes" :key="type">
        <input
          type="checkbox"
          :value="type"
          :checked="ticked(filters.nodeTypes, type)"
          @change="setNodeType(type, checked($event))"
        />
        {{ type }}
      </label>
    </fieldset>

    <fieldset data-test="edge-type-filter" class="types">
      <legend>Edge types</legend>
      <label v-for="type in edgeTypes" :key="type">
        <input
          type="checkbox"
          :value="type"
          :checked="ticked(filters.edgeTypes, type)"
          @change="setEdgeType(type, checked($event))"
        />
        {{ type }}
      </label>
    </fieldset>
  </div>
</template>

<style scoped>
.graph-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem 1.25rem;
  align-items: center;
  font-size: 0.85rem;
  margin-bottom: 0.75rem;
}
.control {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.types {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.75rem;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  padding: 0.25rem 0.6rem 0.4rem;
  flex-basis: 100%;
}
.types legend {
  color: #718096;
  font-size: 0.75rem;
  text-transform: uppercase;
}
.blast-of {
  color: #718096;
}
button {
  border: 1px solid #cbd5e0;
  background: #fff;
  border-radius: 4px;
  padding: 0.25rem 0.8rem;
  cursor: pointer;
}
</style>
