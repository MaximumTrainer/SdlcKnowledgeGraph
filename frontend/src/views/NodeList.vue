<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { nodeApi, ontologyApi, type GraphNode, type OntologyNodeType } from '@/services/api'

/**
 * The list of nodes of one type, with a tab per type the registry declares.
 *
 * The tabs come from `GET /api/v1/ontology` rather than a hand-written list, so a node type added to
 * the registry is reachable here without a frontend release.
 */
const props = defineProps<{ type: string }>()

const nodeTypes = ref<OntologyNodeType[]>([])
const nodes = ref<GraphNode[]>([])
const loading = ref(true)

/** Enough to recognise a node without turning the table into the detail page. */
const columns = computed(
  () =>
    nodeTypes.value
      .find(candidate => candidate.name === props.type)
      ?.properties.slice(0, 3)
      .map(property => property.name) ?? []
)

const load = async () => {
  loading.value = true
  try {
    const [ontology, page] = await Promise.all([ontologyApi.get(), nodeApi.list(props.type)])
    nodeTypes.value = ontology.nodeTypes
    nodes.value = page.items
  } finally {
    loading.value = false
  }
}

watch(() => props.type, load, { immediate: true })

const cell = (node: GraphNode, column: string): string => {
  const value = node.props[column]
  if (value === null || value === undefined) return ''
  return Array.isArray(value) ? value.join(', ') : String(value)
}
</script>

<template>
  <section class="node-list">
    <nav class="tabs">
      <router-link
        v-for="nodeType in nodeTypes"
        :key="nodeType.name"
        data-test="type-tab"
        :class="{ active: nodeType.name === type }"
        :to="`/nodes/${nodeType.name}`"
      >
        {{ nodeType.name }}
      </router-link>
    </nav>

    <header>
      <h1>{{ type }}</h1>
      <router-link data-test="new-node" class="new" :to="`/nodes/${type}/new`">New</router-link>
    </header>

    <p v-if="loading">Loading…</p>
    <p v-else-if="nodes.length === 0">No {{ type }} nodes yet.</p>

    <table v-else>
      <thead>
        <tr>
          <th>key</th>
          <th v-for="column in columns" :key="column">{{ column }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="node in nodes" :key="node.id">
          <td data-test="node-key">
            <router-link :to="`/nodes/${type}/${node.key}`">{{ node.key }}</router-link>
          </td>
          <td v-for="column in columns" :key="column">{{ cell(node, column) }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.node-list {
  background: #fff;
  border-radius: 6px;
  padding: 1.5rem;
}
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  border-bottom: 1px solid #e2e8f0;
  margin-bottom: 1rem;
}
.tabs a {
  padding: 0.4rem 0.75rem;
  font-size: 0.85rem;
  color: #4a5568;
  text-decoration: none;
  border-bottom: 2px solid transparent;
}
.tabs a.active {
  color: #2b6cb0;
  border-bottom-color: #2b6cb0;
  font-weight: 600;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
h1 {
  font-size: 1.25rem;
}
.new {
  background: #2b6cb0;
  color: #fff;
  border-radius: 4px;
  padding: 0.4rem 1rem;
  text-decoration: none;
  font-size: 0.85rem;
}
table {
  width: 100%;
  border-collapse: collapse;
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
