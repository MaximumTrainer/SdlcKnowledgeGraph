<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ontologyApi, type OntologyNodeType } from '@/services/api'
import {
  POLICY_ACTIONS,
  policyApi,
  type PolicyAction,
  type PolicyExplanation,
  type PolicyStatus
} from '@/services/policyApi'
import { formatInstant } from '@/lib/time'

/**
 * The authorisation policy (#30, #95): which revision is in force, how it is evaluated, and what it
 * decides for the signed-in user. Asking it about an action on a node type - or one node, for what
 * owning it allows - shows the answer the API would give, with the rule that gave it and why.
 *
 * It only ever explains the user's own access; nothing here says what anyone else may do.
 */
const status = ref<PolicyStatus | null>(null)
const statusError = ref('')
const nodeTypes = ref<OntologyNodeType[]>([])

const action = ref<PolicyAction>('update')
const type = ref('Repository')
const key = ref('')
const explaining = ref(false)
const explanation = ref<PolicyExplanation | null>(null)
const explainError = ref('')

const sensitivityOf = computed(
  () => nodeTypes.value.find(nodeType => nodeType.name === type.value)?.sensitivity ?? 'internal'
)

const load = async () => {
  try {
    status.value = await policyApi.status()
  } catch {
    statusError.value = 'The policy in force could not be loaded.'
  }
  try {
    nodeTypes.value = (await ontologyApi.get()).nodeTypes
  } catch {
    nodeTypes.value = []
  }
}

const explain = async () => {
  explaining.value = true
  explainError.value = ''
  try {
    explanation.value = await policyApi.explain(action.value, {
      type: type.value || undefined,
      key: key.value.trim() || undefined
    })
  } catch {
    explanation.value = null
    explainError.value = 'The policy could not be asked.'
  } finally {
    explaining.value = false
  }
}

const rolesText = (roles: string[] | null) =>
  roles === null ? 'none: judged by the token’s scopes alone' : roles.join(', ') || 'none'

onMounted(load)
</script>

<template>
  <section class="policy">
    <header>
      <h1>Authorisation policy</h1>
    </header>
    <p class="intro">
      Every request is decided by one policy, compiled from Rego and evaluated inside the API. Ask
      it what it would decide for you.
    </p>

    <p v-if="statusError" class="error" role="alert">{{ statusError }}</p>
    <dl v-if="status" class="status" data-test="policy-status">
      <dt>Revision</dt>
      <dd data-test="policy-revision">{{ status.revision }}</dd>
      <dt>Engine</dt>
      <dd>{{ status.engine }} ({{ status.status }})</dd>
      <dt>When it cannot be evaluated</dt>
      <dd>{{ status.failMode === 'closed' ? 'requests are refused' : status.failMode }}</dd>
      <dt>Loaded</dt>
      <dd>{{ formatInstant(status.loadedAt) }}</dd>
    </dl>

    <section class="panel" aria-labelledby="explain-heading">
      <h2 id="explain-heading">Explain a decision</h2>
      <form class="explain" @submit.prevent="explain">
        <label>
          Action
          <select v-model="action" data-test="explain-action">
            <option v-for="name in POLICY_ACTIONS" :key="name" :value="name">{{ name }}</option>
          </select>
        </label>
        <label>
          Node type
          <select v-model="type" data-test="explain-type">
            <option v-for="nodeType in nodeTypes" :key="nodeType.name" :value="nodeType.name">
              {{ nodeType.name }}
            </option>
            <option v-if="!nodeTypes.length" value="Repository">Repository</option>
          </select>
        </label>
        <label>
          Key <span class="muted">(optional)</span>
          <input
            v-model="key"
            type="text"
            placeholder="github.com/acme/payments"
            data-test="explain-key"
          />
        </label>
        <button type="submit" :disabled="explaining" data-test="explain-submit">Explain</button>
      </form>
      <p class="muted">{{ type }} is labelled {{ sensitivityOf }}.</p>
      <p v-if="explainError" class="error" role="alert">{{ explainError }}</p>

      <div
        v-if="explanation"
        class="answer"
        :class="explanation.allow ? 'allowed' : 'denied'"
        data-test="explain-result"
      >
        <p class="verdict" data-test="explain-verdict">
          {{ explanation.allow ? 'Allowed' : 'Denied' }}
          <span class="rule">{{ explanation.policy }}</span>
        </p>
        <p data-test="explain-reason">{{ explanation.reason }}</p>
        <dl>
          <dt>You are</dt>
          <dd data-test="explain-subject">
            {{ explanation.subject.id }} ({{ explanation.subject.kind }})
          </dd>
          <dt>Roles</dt>
          <dd>{{ rolesText(explanation.subject.roles) }}</dd>
          <dt>Cleared for</dt>
          <dd data-test="explain-clearance">{{ explanation.clearance }}</dd>
          <dt>Needs</dt>
          <dd>{{ explanation.required.join(', ') || 'no scope' }}</dd>
          <template v-if="explanation.redact.length">
            <dt>Hidden from you</dt>
            <dd>{{ explanation.redact.join(', ') }}</dd>
          </template>
        </dl>
      </div>
    </section>
  </section>
</template>

<style scoped>
.policy {
  background: #fff;
  border-radius: 6px;
  padding: 1.5rem;
}
header {
  margin-bottom: 0.5rem;
}
h1 {
  font-size: 1.25rem;
}
h2 {
  font-size: 1rem;
  margin-bottom: 0.5rem;
}
.intro,
.muted {
  color: #4a5568;
}
.intro {
  margin-bottom: 1rem;
}
.panel {
  border-top: 1px solid #edf2f7;
  padding: 1rem 0;
}
dl {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.25rem 1rem;
  margin-bottom: 1rem;
}
dt {
  color: #4a5568;
}
.explain {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
  margin-bottom: 0.5rem;
}
label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.9rem;
}
select,
input {
  padding: 0.3rem 0.5rem;
  border: 1px solid #cbd5e0;
  border-radius: 4px;
}
button {
  padding: 0.35rem 0.9rem;
  border: 1px solid #2b6cb0;
  background: #2b6cb0;
  color: #fff;
  border-radius: 4px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
}
.answer {
  margin-top: 0.75rem;
  padding: 0.75rem 1rem;
  border-radius: 6px;
}
.allowed {
  background: #f0fff4;
  border: 1px solid #9ae6b4;
}
.denied {
  background: #fff5f5;
  border: 1px solid #feb2b2;
}
.verdict {
  font-weight: 700;
  margin-bottom: 0.25rem;
}
.rule {
  margin-left: 0.5rem;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  background: #edf2f7;
  font-family: monospace;
  font-size: 0.85rem;
  font-weight: 400;
}
.error {
  color: #c53030;
}
</style>
