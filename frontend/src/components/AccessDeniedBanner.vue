<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { lastPolicyDenial } from '@/services/policyApi'

/**
 * Says so when the authorisation policy refused something the page asked for (#30): which rule
 * refused it and why, such as a role that may not change a type, or a type above what the user is
 * cleared to see. Cleared when the user dismisses it or moves to another page.
 */
const route = useRoute()

watch(
  () => route?.fullPath,
  () => {
    lastPolicyDenial.value = null
  }
)

const dismiss = () => {
  lastPolicyDenial.value = null
}
</script>

<template>
  <div v-if="lastPolicyDenial" class="access-denied" role="alert" data-test="access-denied">
    <p>
      <strong>Access denied by policy</strong>
      <span class="rule" data-test="access-denied-policy">{{ lastPolicyDenial.policy }}</span>
    </p>
    <p data-test="access-denied-reason">{{ lastPolicyDenial.reason }}</p>
    <button type="button" class="dismiss" data-test="access-denied-dismiss" @click="dismiss">
      Dismiss
    </button>
  </div>
</template>

<style scoped>
.access-denied {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.5rem 1rem;
  margin: 0 0 1rem;
  padding: 0.75rem 1rem;
  border: 1px solid #feb2b2;
  border-radius: 6px;
  background: #fff5f5;
  color: #742a2a;
}
.rule {
  margin-left: 0.5rem;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
  background: #fed7d7;
  font-family: monospace;
  font-size: 0.85rem;
}
.dismiss {
  margin-left: auto;
  border: none;
  background: none;
  color: #742a2a;
  text-decoration: underline;
  cursor: pointer;
}
</style>
