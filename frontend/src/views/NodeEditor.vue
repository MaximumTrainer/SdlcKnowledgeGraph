<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import TagInput from '@/components/fields/TagInput.vue'
import { InvalidGitRemoteError, parseGitRemote } from '@/lib/gitRemote'
import {
  nodeApi,
  ontologyApi,
  type OntologyNodeType,
  type OntologyProperty,
  type PropertyError
} from '@/services/api'

/**
 * One form for every node type, built from `GET /api/v1/ontology`.
 *
 * There is no per-type form because there is no per-type endpoint: a node type added to the registry
 * becomes editable here without a frontend release. The two rules worth naming are that an edit
 * updates the node it is editing (the hand-written editor called create in both modes, so every save
 * left a duplicate behind), and that identity properties are not editable, because identity is
 * derived — changing one does not rename a node, it describes a different one.
 */
const props = defineProps<{ type: string; id?: string }>()

const router = useRouter()
const nodeType = ref<OntologyNodeType | null>(null)
const values = ref<Record<string, unknown>>({})
const ready = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const formError = ref('')
const saving = ref(false)

/**
 * The key a typed remote will be stored under, or why it is not a remote.
 *
 * A Repository is keyed on its remote, and the remote is written six different ways by the tools
 * people copy it from. Showing the resolved key as it is typed is the difference between finding out
 * now and finding out after saving - and it runs the same parser the API does, held to the same test
 * table, so the preview cannot promise something the server will not do (#8).
 */
const remotePreview = computed<{ key: string } | { reason: string } | null>(() => {
  if (props.type !== 'Repository') return null
  const url = String(values.value.url ?? '').trim()
  if (url.length === 0) return null
  try {
    return { key: parseGitRemote(url).key }
  } catch (error) {
    return {
      reason: error instanceof InvalidGitRemoteError ? error.reason : 'it is not a git remote'
    }
  }
})

const editing = computed(() => props.id !== undefined && props.id !== '')

const isIdentity = (property: OntologyProperty) =>
  nodeType.value?.identity.includes(property.name) ?? false

/** Identity is derived, so the server would refuse to move it; the form says so before the trip. */
const isLocked = (property: OntologyProperty) => editing.value && isIdentity(property)

const emptyFor = (property: OntologyProperty): unknown => {
  if (property.type === 'string[]') return []
  if (property.type === 'boolean') return false
  return ''
}

/**
 * The form is not rendered until both the shape and the values are in hand.
 *
 * Assigning them separately left a window where the inputs existed but the stored values had not
 * arrived, so anything typed in that window was overwritten when they did.
 */
const load = async () => {
  ready.value = false
  const ontology = await ontologyApi.get()
  const found = ontology.nodeTypes.find(candidate => candidate.name === props.type) ?? null
  if (!found) {
    nodeType.value = null
    formError.value = `${props.type} is not a node type this graph declares`
    return
  }

  const blank = Object.fromEntries(found.properties.map(p => [p.name, emptyFor(p)]))
  const stored = editing.value ? (await nodeApi.get(props.type, props.id as string)).props : {}

  nodeType.value = found
  values.value = { ...blank, ...stored }
  ready.value = true
}

watch(() => [props.type, props.id], load, { immediate: true })

/**
 * The same required check the server applies, run first so a form that cannot succeed does not make
 * the round trip. The server still enforces it; this only saves the user a wasted request.
 */
const missingRequired = (): Record<string, string> => {
  const errors: Record<string, string> = {}
  for (const property of nodeType.value?.properties ?? []) {
    if (!property.required || isLocked(property)) continue
    const value = values.value[property.name]
    const absent =
      value === null || value === undefined || (typeof value === 'string' && value.trim() === '')
    if (absent) errors[property.name] = `${property.name} is required`
  }
  return errors
}

/** Empty optional values are left out entirely, so a blank field is absence rather than an empty string. */
const submitted = (): Record<string, unknown> => {
  const payload: Record<string, unknown> = {}
  for (const property of nodeType.value?.properties ?? []) {
    const value = values.value[property.name]
    if (value === '' && !property.required) continue
    if (property.type === 'int' && typeof value === 'string') {
      payload[property.name] = value === '' ? undefined : Number(value)
      continue
    }
    payload[property.name] = value
  }
  return payload
}

const describe = (data: { error?: string; errors?: PropertyError[]; existingId?: string }) => {
  if (data?.errors) {
    fieldErrors.value = Object.fromEntries(data.errors.map(e => [e.field, e.message]))
    return ''
  }
  if (data?.error === 'node exists') return 'A node with this identity already exists.'
  if (data?.error === 'identity properties are immutable') {
    return 'Identity properties cannot be changed; create a new node instead.'
  }
  return data?.error ?? 'The server refused this change.'
}

const save = async () => {
  formError.value = ''
  fieldErrors.value = missingRequired()
  if (Object.keys(fieldErrors.value).length > 0) return

  saving.value = true
  try {
    const saved = editing.value
      ? await nodeApi.update(props.type, props.id as string, submitted())
      : await nodeApi.create(props.type, submitted())
    await router.push(`/nodes/${props.type}/${saved.key}`)
  } catch (error) {
    const response = (error as { response?: { data?: Record<string, never> } }).response
    formError.value = describe(response?.data ?? {})
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="node-editor">
    <h1>{{ editing ? 'Edit' : 'New' }} {{ type }}</h1>

    <p v-if="formError" class="form-error" data-test="form-error">{{ formError }}</p>

    <form v-if="ready && nodeType" @submit.prevent="save">
      <div v-for="property in nodeType.properties" :key="property.name" class="field">
        <label :for="`field-${property.name}`">
          {{ property.name }}<span v-if="property.required" aria-hidden="true">&nbsp;*</span>
        </label>

        <TagInput
          v-if="property.type === 'string[]'"
          v-model="values[property.name] as string[]"
          :name="property.name"
          :disabled="isLocked(property)"
        />
        <input
          v-else-if="property.type === 'boolean'"
          :id="`field-${property.name}`"
          v-model="values[property.name]"
          :name="property.name"
          :disabled="isLocked(property)"
          type="checkbox"
        />
        <input
          v-else
          :id="`field-${property.name}`"
          v-model="values[property.name]"
          :name="property.name"
          :disabled="isLocked(property)"
          :type="
            property.type === 'int'
              ? 'number'
              : property.type === 'instant'
                ? 'datetime-local'
                : 'text'
          "
        />

        <small
          v-if="property.name === 'url' && remotePreview"
          class="hint"
          data-test="remote-key"
          :class="{ 'field-error': 'reason' in remotePreview }"
        >
          <template v-if="'key' in remotePreview">Key: {{ remotePreview.key }}</template>
          <template v-else>Not a git remote: {{ remotePreview.reason }}</template>
        </small>

        <small v-if="property.description" class="hint">{{ property.description }}</small>
        <small v-if="isLocked(property)" class="hint">
          Part of this node's identity, so it cannot be changed.
        </small>
        <small
          v-if="fieldErrors[property.name]"
          class="field-error"
          :data-test="`error-${property.name}`"
        >
          {{ fieldErrors[property.name] }}
        </small>
      </div>

      <div class="actions">
        <button type="submit" :disabled="saving">Save</button>
        <router-link :to="`/nodes/${type}`">Cancel</router-link>
      </div>
    </form>
  </section>
</template>

<style scoped>
.node-editor {
  background: #fff;
  border-radius: 6px;
  padding: 1.5rem;
}
h1 {
  font-size: 1.25rem;
  margin-bottom: 1rem;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-bottom: 1rem;
  max-width: 32rem;
}
label {
  font-size: 0.85rem;
  font-weight: 600;
  color: #2d3748;
}
input[type='text'],
input[type='number'],
input[type='datetime-local'] {
  border: 1px solid #cbd5e0;
  border-radius: 4px;
  padding: 0.45rem;
}
input:disabled {
  background: #edf2f7;
  color: #718096;
}
.hint {
  color: #718096;
  font-size: 0.75rem;
}
.field-error,
.form-error {
  color: #c53030;
  font-size: 0.8rem;
}
.form-error {
  margin-bottom: 1rem;
}
.actions {
  display: flex;
  gap: 1rem;
  align-items: center;
}
button {
  background: #2b6cb0;
  color: #fff;
  border: 0;
  border-radius: 4px;
  padding: 0.5rem 1.25rem;
  cursor: pointer;
}
button:disabled {
  background: #a0aec0;
}
</style>
