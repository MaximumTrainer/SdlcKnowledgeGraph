<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  edgeApi,
  nodeApi,
  ontologyApi,
  type EdgeView,
  type GraphNode,
  type OntologyEdgeType,
  type PropertyError
} from '@/services/api'

/**
 * The relationships of one node, from that node's point of view.
 *
 * Everything here is driven by the registry. The edge types offered are the ones the ontology allows
 * with this node at one end, so the panel cannot propose a relationship the server would refuse, and
 * a relationship added to the registry appears here without a frontend release.
 *
 * Edges are grouped by `displayName` rather than by `type`: a Team sees `OWNS` where the Repository
 * at the other end sees `OWNED_BY`, and that is the same stored edge read from the other side.
 */
const props = defineProps<{ type: string; nodeKey: string }>()

const edges = ref<EdgeView[]>([])
const edgeTypes = ref<OntologyEdgeType[]>([])
const adding = ref(false)
const error = ref('')

const chosenType = ref('')
const targetQuery = ref('')
const targetKey = ref('')
const candidates = ref<GraphNode[]>([])
const edgeProps = ref<Record<string, string>>({})

const nodeId = computed(() => `${props.type}:${props.nodeKey}`)

/** Edge types with this node type at either end; anything else the server would refuse anyway. */
const offered = computed(() =>
  edgeTypes.value.filter(edge => edge.from.includes(props.type) || edge.to.includes(props.type))
)

const chosen = computed(() => edgeTypes.value.find(edge => edge.name === chosenType.value) ?? null)

/** Which node type sits at the far end, given which end this node is on. */
const targetTypes = computed(() => {
  if (!chosen.value) return []
  return chosen.value.from.includes(props.type) ? chosen.value.to : chosen.value.from
})

const grouped = computed(() => {
  const groups = new Map<string, EdgeView[]>()
  for (const edge of edges.value) {
    const existing = groups.get(edge.displayName)
    if (existing) existing.push(edge)
    else groups.set(edge.displayName, [edge])
  }
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))
})

const load = async () => {
  const [ontology, found] = await Promise.all([
    ontologyApi.get(),
    edgeApi.forNode(props.type, props.nodeKey)
  ])
  edgeTypes.value = ontology.edgeTypes
  edges.value = found
}

watch(() => [props.type, props.nodeKey], load, { immediate: true })

watch(chosenType, () => {
  targetKey.value = ''
  targetQuery.value = ''
  candidates.value = []
  edgeProps.value = {}
})

const searchTargets = async () => {
  const type = targetTypes.value[0]
  if (!type) return
  const page = await nodeApi.list(type)
  const query = targetQuery.value.toLowerCase()
  candidates.value = page.items.filter(item => item.key.toLowerCase().includes(query)).slice(0, 10)
}

const describe = (data: { error?: string; errors?: PropertyError[] }) => {
  if (data?.errors) return data.errors.map(e => e.message).join('; ')
  if (data?.error === 'edge not allowed') return 'The ontology does not allow that relationship.'
  if (data?.error === 'node not found') return 'One end of that relationship does not exist.'
  if (data?.error === 'self edge') return 'A node cannot be related to itself.'
  return data?.error ?? 'The server refused that relationship.'
}

const submit = async () => {
  error.value = ''
  const type = targetTypes.value[0]
  if (!chosen.value || !targetKey.value || !type) return

  // Which end this node sits on decides the direction; the registry already told us.
  const thisEndIsFrom = chosen.value.from.includes(props.type)
  const otherId = `${type}:${targetKey.value}`

  try {
    await edgeApi.create({
      type: chosen.value.name,
      fromId: thisEndIsFrom ? nodeId.value : otherId,
      toId: thisEndIsFrom ? otherId : nodeId.value,
      props: edgeProps.value
    })
    adding.value = false
    chosenType.value = ''
    await load()
  } catch (caught) {
    error.value = describe((caught as { response?: { data?: never } }).response?.data ?? {})
  }
}

const remove = async (edge: EdgeView) => {
  error.value = ''
  const outgoing = edge.direction === 'out'
  try {
    await edgeApi.remove(
      edge.type,
      outgoing ? nodeId.value : edge.other.id,
      outgoing ? edge.other.id : nodeId.value
    )
    await load()
  } catch (caught) {
    error.value = describe((caught as { response?: { data?: never } }).response?.data ?? {})
  }
}
</script>

<template>
  <section class="relationships" data-testid="relationship-panel">
    <header>
      <h2>Relationships</h2>
      <button type="button" @click="adding = !adding">
        {{ adding ? 'Cancel' : 'Add relationship' }}
      </button>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <form v-if="adding" class="add" @submit.prevent="submit">
      <label for="edge-type">Relationship</label>
      <select id="edge-type" v-model="chosenType">
        <option value="" disabled>Choose one</option>
        <option v-for="edge in offered" :key="edge.name" :value="edge.name">{{ edge.name }}</option>
      </select>

      <template v-if="chosen">
        <label for="edge-target">Target</label>
        <input
          id="edge-target"
          v-model="targetQuery"
          type="text"
          :placeholder="`Search ${targetTypes.join(' or ')}`"
          @input="searchTargets"
        />
        <ul v-if="candidates.length" class="candidates" role="listbox">
          <li v-for="candidate in candidates" :key="candidate.id">
            <button type="button" role="option" @click="targetKey = candidate.key">
              {{ candidate.key }}
            </button>
          </li>
        </ul>
        <p v-if="targetKey" class="chosen-target">Selected: {{ targetKey }}</p>

        <template v-for="property in chosen.properties" :key="property.name">
          <label :for="`edge-prop-${property.name}`">
            {{ property.name }}<span v-if="property.required" aria-hidden="true">&nbsp;*</span>
          </label>
          <select
            v-if="property.enum"
            :id="`edge-prop-${property.name}`"
            v-model="edgeProps[property.name]"
          >
            <option value="" disabled>Choose one</option>
            <option v-for="value in property.enum" :key="value" :value="value">{{ value }}</option>
          </select>
          <input
            v-else
            :id="`edge-prop-${property.name}`"
            v-model="edgeProps[property.name]"
            type="text"
          />
        </template>

        <button type="submit" :disabled="!targetKey">Add</button>
      </template>
    </form>

    <!-- The list is its own region so a test cannot satisfy an assertion from the add form, whose
         type dropdown contains every edge name. -->
    <div data-testid="relationship-list">
      <p v-if="edges.length === 0">Nothing is related to this yet.</p>

      <div v-for="[displayName, group] in grouped" :key="displayName" class="group">
        <h3>{{ displayName }}</h3>
        <ul>
          <li v-for="edge in group" :key="`${edge.type}-${edge.other.id}`">
            <router-link :to="`/nodes/${edge.other.type}/${edge.other.key}`">
              {{ edge.other.key }}
            </router-link>
            <span v-if="Object.keys(edge.props).length" class="props">
              {{
                Object.entries(edge.props)
                  .map(([k, v]) => `${k}: ${v}`)
                  .join(', ')
              }}
            </span>
            <button
              type="button"
              class="remove"
              :aria-label="`Remove ${displayName} to ${edge.other.key}`"
              @click="remove(edge)"
            >
              ×
            </button>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.relationships {
  margin-top: 2rem;
  border-top: 1px solid #e2e8f0;
  padding-top: 1rem;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
h2 {
  font-size: 0.8rem;
  text-transform: uppercase;
  color: #718096;
}
h3 {
  font-size: 0.8rem;
  margin: 1rem 0 0.35rem;
  color: #2b6cb0;
}
ul {
  list-style: none;
  padding: 0;
}
li {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.3rem 0;
  font-size: 0.9rem;
}
.props {
  color: #718096;
  font-size: 0.8rem;
}
.add {
  display: grid;
  grid-template-columns: 10rem 1fr;
  gap: 0.5rem;
  align-items: center;
  margin: 1rem 0;
  padding: 1rem;
  background: #f7fafc;
  border-radius: 4px;
}
.add label {
  font-size: 0.85rem;
  font-weight: 600;
}
.add input,
.add select {
  border: 1px solid #cbd5e0;
  border-radius: 4px;
  padding: 0.4rem;
}
.candidates {
  grid-column: 2;
  border: 1px solid #cbd5e0;
  border-radius: 4px;
  background: #fff;
  max-height: 10rem;
  overflow-y: auto;
}
.candidates button {
  width: 100%;
  text-align: left;
  border: 0;
  background: none;
  padding: 0.3rem 0.5rem;
  cursor: pointer;
}
.chosen-target {
  grid-column: 2;
  font-size: 0.8rem;
  color: #2f855a;
}
button {
  border: 1px solid #cbd5e0;
  background: #fff;
  border-radius: 4px;
  padding: 0.3rem 0.8rem;
  cursor: pointer;
}
.remove {
  border: 0;
  color: #c53030;
  padding: 0 0.4rem;
}
.error {
  color: #c53030;
  font-size: 0.85rem;
}
</style>
