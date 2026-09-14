<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import RelationshipPanel from '@/components/RelationshipPanel.vue'
import { nodeApi, type GraphNode } from '@/services/api'
import { parseGitRemote } from '@/lib/gitRemote'

/**
 * One node: what it says, and who said it.
 *
 * The provenance panel is not decoration. A graph assembled from several systems is only usable if a
 * reader can tell "ServiceNow reports this" from "a rule guessed it", and that distinction has to be
 * visible on the thing itself rather than buried in an audit log.
 */
const props = defineProps<{ type: string; id: string }>()

const router = useRouter()
const node = ref<GraphNode | null>(null)
const error = ref('')
const confirming = ref(false)
const edgeCount = ref(0)

/**
 * The remote this node points at, when it points at one.
 *
 * Built from the stored canonical url rather than reassembled from the parts, so the link cannot
 * claim somewhere the graph does not. The label follows the host, because a GitLab repository saying
 * "Open in GitHub" would be worse than no link (#8).
 */
const remote = computed(() => {
  const url = node.value?.props?.url
  if (typeof url !== 'string' || url.length === 0) return null
  try {
    const parsed = parseGitRemote(url)
    return { href: parsed.canonicalUrl, host: parsed.host }
  } catch {
    // A stored url that will not parse is a graph problem, not something to shout about here.
    return null
  }
})

const load = async () => {
  error.value = ''
  try {
    node.value = await nodeApi.get(props.type, props.id)
  } catch {
    node.value = null
    error.value = 'That node does not exist.'
  }
}

watch(() => [props.type, props.id], load, { immediate: true })

const display = (value: unknown): string => {
  if (value === null || value === undefined) return '—'
  return Array.isArray(value) ? value.join(', ') : String(value)
}

const remove = async (cascade: boolean) => {
  error.value = ''
  try {
    await nodeApi.remove(props.type, props.id, { cascade })
    await router.push(`/nodes/${props.type}`)
  } catch (caught) {
    const data = (caught as { response?: { data?: { error?: string; edgeCount?: number } } })
      .response?.data
    if (data?.error === 'node has edges') {
      edgeCount.value = data.edgeCount ?? 0
      confirming.value = true
      return
    }
    error.value = data?.error ?? 'That node could not be deleted.'
  }
}
</script>

<template>
  <section class="node-detail">
    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="node">
      <header>
        <div>
          <p class="type">{{ node.type }}</p>
          <h1>{{ node.key }}</h1>
        </div>
        <div class="actions">
          <router-link :to="`/nodes/${type}/${id}/edit`">Edit</router-link>
          <button type="button" class="danger" @click="remove(false)">Delete</button>
        </div>
      </header>

      <div v-if="confirming" class="confirm" data-test="cascade-confirm">
        <p>This node still has {{ edgeCount }} edge{{ edgeCount === 1 ? '' : 's' }}.</p>
        <button type="button" class="danger" @click="remove(true)">Delete it and its edges</button>
        <button type="button" @click="confirming = false">Cancel</button>
      </div>

      <p v-if="remote" class="remote">
        <a :href="remote.href" target="_blank" rel="noopener noreferrer" data-test="open-remote"
          >Open in {{ remote.host }}</a
        >
      </p>

      <h2>Properties</h2>
      <dl>
        <template v-for="(value, name) in node.props" :key="name">
          <dt>{{ name }}</dt>
          <dd>{{ display(value) }}</dd>
        </template>
      </dl>

      <RelationshipPanel :type="type" :node-key="node.key" />

      <h2>Provenance</h2>
      <dl data-test="provenance">
        <dt>source</dt>
        <dd>{{ node.provenance.sourceSystem }}</dd>
        <dt>confidence</dt>
        <dd>{{ node.provenance.confidence }}</dd>
        <dt>ingested</dt>
        <dd>{{ node.provenance.ingestedAt }}</dd>
        <dt>inferred</dt>
        <dd>{{ node.provenance.inferred ? 'yes' : 'no' }}</dd>
      </dl>
    </template>
  </section>
</template>

<style scoped>
.node-detail {
  background: #fff;
  border-radius: 6px;
  padding: 1.5rem;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1.5rem;
}
.type {
  color: #718096;
  font-size: 0.75rem;
  text-transform: uppercase;
}
h1 {
  font-size: 1.25rem;
}
h2 {
  font-size: 0.8rem;
  text-transform: uppercase;
  color: #718096;
  margin: 1.5rem 0 0.5rem;
}
.actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}
dl {
  display: grid;
  grid-template-columns: 12rem 1fr;
  gap: 0.4rem 1rem;
  font-size: 0.9rem;
}
dt {
  color: #4a5568;
  font-weight: 600;
}
.confirm {
  background: #fffaf0;
  border: 1px solid #f6ad55;
  border-radius: 4px;
  padding: 1rem;
  display: flex;
  gap: 0.75rem;
  align-items: center;
  font-size: 0.9rem;
}
button {
  border: 1px solid #cbd5e0;
  background: #fff;
  border-radius: 4px;
  padding: 0.35rem 0.9rem;
  cursor: pointer;
}
.danger {
  border-color: #c53030;
  color: #c53030;
}
.error {
  color: #c53030;
  margin-bottom: 1rem;
}
</style>
