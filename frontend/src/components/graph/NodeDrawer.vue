<script setup lang="ts">
import { computed } from 'vue'
import type { SubgraphNode } from '@/services/api'
import { nodeRoute } from '@/lib/nodeRoute'

/**
 * The drawer a tapped node opens on the graph view (#9, FR7): what the node says and who said it,
 * with "Expand" to draw its own neighbourhood beside it and "Open" for its full page.
 *
 * A node drawn from a blast radius arrives without provenance, since the impact answer does not
 * carry it; the drawer says so rather than showing an empty one.
 */
const props = defineProps<{ node: SubgraphNode; expanding?: boolean }>()
const emit = defineEmits<{ expand: [id: string]; close: [] }>()

// The key goes in as path segments: the node routes read a key with slashes that way.
const openHref = computed(() => nodeRoute(props.node.type, props.node.key))

const display = (value: unknown): string => {
  if (value === null || value === undefined) return '—'
  return Array.isArray(value) ? value.join(', ') : String(value)
}
</script>

<template>
  <aside class="node-drawer" data-test="node-drawer" :aria-label="`Node ${node.label}`">
    <header>
      <div>
        <p class="type">{{ node.type }}</p>
        <h2>{{ node.label }}</h2>
        <p class="key" data-test="drawer-key">{{ node.key }}</p>
      </div>
      <button type="button" class="close" aria-label="Close" @click="emit('close')">×</button>
    </header>

    <div class="actions">
      <button
        type="button"
        data-test="drawer-expand"
        :disabled="expanding"
        @click="emit('expand', node.id)"
      >
        Expand
      </button>
      <router-link data-test="drawer-open" :to="openHref">Open</router-link>
    </div>

    <h3>Properties</h3>
    <dl>
      <template v-for="(value, name) in node.props" :key="name">
        <dt>{{ name }}</dt>
        <dd>{{ display(value) }}</dd>
      </template>
    </dl>

    <h3>Provenance</h3>
    <dl v-if="node.provenance" data-test="drawer-provenance">
      <dt>source</dt>
      <dd data-test="drawer-source-system">{{ node.provenance.sourceSystem }}</dd>
      <dt>confidence</dt>
      <dd>{{ node.provenance.confidence }}</dd>
      <dt>inferred</dt>
      <dd>{{ node.provenance.inferred ? 'yes' : 'no' }}</dd>
      <dt>written by</dt>
      <dd>{{ node.provenance.writtenBy ?? 'not recorded' }}</dd>
    </dl>
    <p v-else class="muted">Not in this answer: open the node to see who stated it.</p>
  </aside>
</template>

<style scoped>
.node-drawer {
  background: #fff;
  border-left: 1px solid #e2e8f0;
  padding: 1rem 1.25rem;
  width: 20rem;
  max-width: 100%;
  overflow-y: auto;
  font-size: 0.85rem;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.type {
  color: #718096;
  font-size: 0.7rem;
  text-transform: uppercase;
}
h2 {
  font-size: 1.1rem;
  margin: 0.1rem 0;
}
.key {
  color: #4a5568;
  word-break: break-all;
}
h3 {
  font-size: 0.75rem;
  text-transform: uppercase;
  color: #718096;
  margin: 1rem 0 0.4rem;
}
.actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.75rem;
}
dl {
  display: grid;
  grid-template-columns: 7rem 1fr;
  gap: 0.3rem 0.75rem;
}
dt {
  color: #4a5568;
  font-weight: 600;
}
dd {
  word-break: break-word;
}
.muted {
  color: #718096;
}
button {
  border: 1px solid #cbd5e0;
  background: #fff;
  border-radius: 4px;
  padding: 0.25rem 0.8rem;
  cursor: pointer;
}
.close {
  border: none;
  font-size: 1.2rem;
  line-height: 1;
  padding: 0 0.25rem;
}
</style>
