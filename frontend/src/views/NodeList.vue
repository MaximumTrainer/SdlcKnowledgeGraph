<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { nodeApi, ontologyApi, type GraphNode, type OntologyNodeType } from '@/services/api'
import { useCanWrite } from '@/auth/canWrite'
import { nodeRoute } from '@/lib/nodeRoute'
import SourceLag from '@/components/SourceLag.vue'

/**
 * The list of nodes of one type. Moving between types is the shell's navigation (#6); the ontology
 * is read here only for the columns worth showing.
 */
const props = defineProps<{ type: string }>()

// A user who may only read is not offered a form the API would refuse (#116).
const canWrite = useCanWrite()

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
    <header>
      <h1>{{ type }}</h1>
      <router-link v-if="canWrite" data-test="new-node" class="new" :to="`/nodes/${type}/new`"
        >New</router-link
      >
    </header>

    <SourceLag />

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
            <router-link :to="nodeRoute(type, node.key)">{{ node.key }}</router-link>
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
