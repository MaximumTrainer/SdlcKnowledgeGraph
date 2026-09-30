<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import cytoscape, { type Core, type LayoutOptions, type StylesheetJson } from 'cytoscape'
import dagre from 'cytoscape-dagre'
import GraphToolbar from '@/components/graph/GraphToolbar.vue'
import NodeDrawer from '@/components/graph/NodeDrawer.vue'
import GraphLegend from '@/components/graph/GraphLegend.vue'
import {
  graphApi,
  impactApi,
  ontologyApi,
  type Ontology,
  type Subgraph,
  type SubgraphNode
} from '@/services/api'
import { graphStylesheet, toElements } from '@/lib/cyStyles'
import { impactOverlay, mergeSubgraphs } from '@/lib/graphMerge'
import { filtersFromQuery, filtersToQuery, type GraphFilters } from '@/lib/graphQuery'

cytoscape.use(dagre)

/**
 * The graph view (#9): the neighbourhood of one node drawn as a graph, to be widened a node at a
 * time, narrowed by type and direction, and overlaid with what a change to a node would reach.
 *
 * Everything drawn comes from `GET /graph/neighbourhood`, and what is drawn is merged by id, so an
 * expansion adds only what is new and leaves what a reader has already arranged where they put it.
 * The filters live in the query string, so a narrowed view is a link. The blast radius is #21's
 * `GET /graph/impact` from the selected node - or the root, when nothing is selected - to the depth
 * the view shows, counting only links held with confidence 0.5 or more.
 *
 * The canvas is Cytoscape's. In test mode (and with `?e2e=1`) the instance is handed to the browser
 * tests as `window.__cy`, with `window.__cyReady` set once a layout has stopped, since a canvas has
 * no DOM for a test to read.
 */
const props = defineProps<{ nodeId?: string }>()

const route = useRoute()
const router = useRouter()

const BLAST_MIN_CONFIDENCE = 0.5
const EXPANSION_RADIUS = 140

const ontology = ref<Ontology | null>(null)
const subgraph = shallowRef<Subgraph | null>(null)
const error = ref('')
const loading = ref(false)
const expanding = ref(false)
const selectedId = ref<string | null>(null)
const blast = ref(false)
const blastCounts = ref<string[] | null>(null)
const blastFocus = ref<string | null>(null)
const container = ref<HTMLDivElement | null>(null)
const view = ref<HTMLElement | null>(null)
const startType = ref('')
const startKey = ref('')

let cy: Core | null = null
let loads = 0
let viewSize: ResizeObserver | null = null

const exposed = () => import.meta.env.MODE === 'test' || route.query.e2e === '1'
const setReady = (ready: boolean) => {
  if (exposed()) window.__cyReady = ready
}

const filters = computed<GraphFilters>(() => filtersFromQuery(route.query))

// The graph's own bookkeeping types are not offered, as the navigation leaves them out.
const browsable = computed(() => (ontology.value?.nodeTypes ?? []).filter(type => !type.meta))
const nodeTypeNames = computed(() => browsable.value.map(type => type.name))
const edgeTypeNames = computed(() => {
  const offered = new Set(nodeTypeNames.value)
  return (ontology.value?.edgeTypes ?? [])
    .filter(
      type => type.from.some(name => offered.has(name)) && type.to.some(name => offered.has(name))
    )
    .map(type => type.name)
})
const displayProperties = computed(() =>
  Object.fromEntries((ontology.value?.nodeTypes ?? []).map(t => [t.name, t.displayProperty]))
)

const drawnTypes = computed(() => {
  const drawn = new Set((subgraph.value?.nodes ?? []).map(node => node.type))
  const ordered = (ontology.value?.nodeTypes ?? []).map(type => type.name)
  return [
    ...ordered.filter(name => drawn.has(name)),
    ...[...drawn].filter(n => !ordered.includes(n))
  ]
})

const nodeById = (id: string | null): SubgraphNode | null =>
  (id && subgraph.value?.nodes.find(node => node.id === id)) || null
const selected = computed(() => nodeById(selectedId.value))
const blastOf = computed(() => nodeById(blastFocus.value)?.label ?? null)

const ensureCanvas = (): Core | null => {
  if (cy) return cy
  if (!container.value) return null
  cy = cytoscape({
    container: container.value,
    style: graphStylesheet as unknown as StylesheetJson,
    elements: []
  })
  cy.on('tap', 'node', event => {
    selectedId.value = event.target.id()
  })
  cy.on('dbltap', 'node', event => {
    void expand(event.target.id())
  })
  cy.on('tap', event => {
    if (event.target === cy) selectedId.value = null
  })
  if (exposed()) window.__cy = cy
  return cy
}

const layOut = (canvas: Core) => {
  const options = {
    name: 'dagre',
    rankDir: 'LR',
    fit: true,
    padding: 30,
    nodeSep: 30,
    rankSep: 90,
    stop: () => setReady(true)
  }
  canvas.layout(options as unknown as LayoutOptions).run()
}

const refusal = (caught: unknown): string => {
  const response = (caught as { response?: { status?: number; data?: Record<string, unknown> } })
    .response
  if (response?.status === 404) return 'That node does not exist.'
  const detail = response?.data?.detail ?? response?.data?.error
  return typeof detail === 'string' ? detail : 'The graph could not be loaded.'
}

const load = async () => {
  if (!props.nodeId) return
  const load = ++loads
  setReady(false)
  loading.value = true
  error.value = ''
  const { depth, nodeTypes, edgeTypes, direction } = filters.value
  try {
    const answer = await graphApi.neighbourhood({
      nodeId: props.nodeId,
      depth,
      nodeTypes,
      edgeTypes,
      direction
    })
    if (load !== loads) return
    subgraph.value = answer
    if (selectedId.value && !nodeById(selectedId.value)) selectedId.value = null
    const canvas = ensureCanvas()
    if (!canvas) return
    canvas.elements().remove()
    canvas.add(toElements(answer))
    layOut(canvas)
    if (blast.value) await showBlastRadius()
  } catch (caught) {
    if (load !== loads) return
    subgraph.value = null
    cy?.elements().remove()
    error.value = refusal(caught)
  } finally {
    if (load === loads) loading.value = false
  }
}

/** Adds what is new to the canvas, placed in a ring around [anchor], and runs no layout. */
const addAround = (anchor: string, incoming: Subgraph) => {
  const current = subgraph.value
  const canvas = cy
  if (!current || !canvas) return
  const { merged, added } = mergeSubgraphs(current, incoming)
  subgraph.value = merged
  if (!added.nodes.length && !added.edges.length) return
  canvas.add(toElements({ ...merged, nodes: added.nodes, edges: added.edges }))
  const centre = canvas.getElementById(anchor).position()
  added.nodes.forEach((node, index) => {
    const angle = (2 * Math.PI * index) / added.nodes.length - Math.PI / 2
    canvas.getElementById(node.id).position({
      x: centre.x + EXPANSION_RADIUS * Math.cos(angle),
      y: centre.y + EXPANSION_RADIUS * Math.sin(angle)
    })
  })
}

const expand = async (id: string) => {
  if (!subgraph.value) return
  expanding.value = true
  setReady(false)
  try {
    const { nodeTypes, edgeTypes, direction } = filters.value
    const incoming = await graphApi.neighbourhood({
      nodeId: id,
      depth: 1,
      nodeTypes,
      edgeTypes,
      direction
    })
    addAround(id, incoming)
    if (blast.value) markBlastRadius()
    error.value = ''
  } catch (caught) {
    error.value = refusal(caught)
  } finally {
    expanding.value = false
    setReady(true)
  }
}

let affected = new Set<string>()
let affectedEdges = new Set<string>()

const clearBlastRadius = () => {
  cy?.elements().removeClass('affected dimmed blast-origin')
  blastCounts.value = null
  blastFocus.value = null
}

const markBlastRadius = () => {
  if (!cy || !blastFocus.value) return
  const canvas = cy
  canvas.elements().removeClass('affected dimmed blast-origin')
  canvas.getElementById(blastFocus.value).addClass('blast-origin')
  for (const node of subgraph.value?.nodes ?? []) {
    if (node.id === blastFocus.value) continue
    canvas.getElementById(node.id).addClass(affected.has(node.id) ? 'affected' : 'dimmed')
  }
  for (const edge of subgraph.value?.edges ?? []) {
    canvas.getElementById(edge.id).addClass(affectedEdges.has(edge.id) ? 'affected' : 'dimmed')
  }
}

const showBlastRadius = async () => {
  const root = subgraph.value?.root
  const focus = selectedId.value ?? root
  if (!focus || !ontology.value) return
  try {
    const impact = await impactApi.impact(focus, filters.value.depth, BLAST_MIN_CONFIDENCE)
    if (!blast.value) return
    const overlay = impactOverlay(impact, ontology.value.edgeTypes, displayProperties.value)
    addAround(focus, overlay.subgraph)
    affected = overlay.affected
    affectedEdges = new Set(overlay.subgraph.edges.map(edge => edge.id))
    blastFocus.value = focus
    blastCounts.value = Object.entries(impact.byType)
      .filter(([type, count]) => type !== 'excluded' && count > 0)
      .map(([type, count]) => `${type}: ${count}`)
    markBlastRadius()
  } catch (caught) {
    error.value = refusal(caught)
  }
}

const setBlast = async (on: boolean) => {
  blast.value = on
  if (on) await showBlastRadius()
  else clearBlastRadius()
}

// A different node selected while the overlay is on moves the overlay to it.
watch(selectedId, () => {
  if (blast.value) void showBlastRadius()
})

const setFilters = (next: GraphFilters) =>
  router.replace({ query: filtersToQuery(next, route.query) })

const fit = () => cy?.fit(undefined, 30)

const start = () => {
  const type = startType.value || nodeTypeNames.value[0]
  const key = startKey.value.trim()
  if (!type || !key) return
  void router.push(`/graph/${encodeURIComponent(`${type}:${key}`)}`)
}

onMounted(() => {
  // Cytoscape maps a click through where the canvas sat when it last measured it, and measures again
  // only when the canvas itself resizes or scrolls. The type filters fill in when the ontology
  // arrives - which can be after the graph is drawn - and a badge or an error can appear above the
  // canvas, each pushing it down without resizing it. Anything that changes the view's size may
  // have moved the canvas, so the canvas is told to measure again.
  if (typeof ResizeObserver !== 'undefined' && view.value) {
    viewSize = new ResizeObserver(() => cy?.resize())
    viewSize.observe(view.value)
  }
  ontologyApi
    .get()
    .then(answer => {
      ontology.value = answer
      if (!startType.value) startType.value = browsable.value[0]?.name ?? ''
    })
    .catch(() => {
      ontology.value = null
    })
})

// The node and the filters are what is fetched; any other change to the query (`e2e`) is not.
watch(
  () => JSON.stringify([props.nodeId ?? null, filters.value]),
  () => {
    selectedId.value = null
    void load()
  },
  { immediate: true, flush: 'post' }
)

onBeforeUnmount(() => {
  viewSize?.disconnect()
  viewSize = null
  cy?.destroy()
  if (cy && window.__cy === cy) window.__cy = undefined
  cy = null
})
</script>

<template>
  <section ref="view" class="graph-view">
    <form v-if="!nodeId" class="start" data-test="start" @submit.prevent="start">
      <h1>Graph</h1>
      <p>Pick a node to draw the neighbourhood of.</p>
      <label>
        Type
        <select v-model="startType" data-test="start-type">
          <option v-for="type in nodeTypeNames" :key="type" :value="type">{{ type }}</option>
        </select>
      </label>
      <label>
        Key
        <input
          v-model="startKey"
          data-test="start-key"
          placeholder="github.com/acme/payments"
          required
        />
      </label>
      <button type="submit">Draw</button>
    </form>

    <template v-else>
      <header class="graph-header">
        <h1>
          Graph of <span class="root">{{ nodeById(subgraph?.root ?? null)?.label ?? nodeId }}</span>
        </h1>
        <span v-if="loading" class="muted" aria-live="polite">Loading…</span>
      </header>

      <GraphToolbar
        :filters="filters"
        :node-types="nodeTypeNames"
        :edge-types="edgeTypeNames"
        :blast="blast"
        :blast-of="blast ? blastOf : null"
        @update:filters="setFilters"
        @update:blast="setBlast"
        @fit="fit"
      />

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="subgraph?.truncated" class="truncated" role="status" data-test="truncated">
        Showing 500 of more nodes; narrow the filters
      </p>
      <p v-if="blast && blastCounts" class="badge" data-test="blast-radius-badge">
        <template v-if="blastCounts.length">Affected: {{ blastCounts.join(', ') }}</template>
        <template v-else>Nothing downstream reached</template>
      </p>

      <div class="stage">
        <div
          ref="container"
          class="canvas"
          data-test="graph-canvas"
          role="img"
          :aria-label="`Graph of ${nodeId}`"
        />
        <NodeDrawer
          v-if="selected"
          :node="selected"
          :expanding="expanding"
          @expand="expand"
          @close="selectedId = null"
        />
      </div>

      <GraphLegend :types="drawnTypes" />
    </template>
  </section>
</template>

<style scoped>
.graph-view h1 {
  font-size: 1.3rem;
  margin-bottom: 0.75rem;
}
.graph-header {
  display: flex;
  gap: 1rem;
  align-items: baseline;
}
.root {
  font-family: ui-monospace, monospace;
}
.stage {
  display: flex;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  height: 70vh;
  min-height: 24rem;
}
.canvas {
  flex: 1;
  min-width: 0;
  height: 100%;
}
.error {
  color: #c53030;
  margin-bottom: 0.5rem;
}
.truncated {
  background: #fefcbf;
  border: 1px solid #ecc94b;
  padding: 0.4rem 0.75rem;
  border-radius: 4px;
  margin-bottom: 0.5rem;
}
.badge {
  display: inline-block;
  background: #fff5f5;
  border: 1px solid #e53e3e;
  color: #9b2c2c;
  padding: 0.3rem 0.75rem;
  border-radius: 999px;
  font-size: 0.8rem;
  margin-bottom: 0.5rem;
}
.muted {
  color: #718096;
}
.start {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-width: 28rem;
}
.start label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.start button {
  align-self: flex-start;
  border: 1px solid #cbd5e0;
  background: #fff;
  border-radius: 4px;
  padding: 0.3rem 1rem;
  cursor: pointer;
}
</style>
