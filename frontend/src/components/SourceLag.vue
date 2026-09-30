<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { freshnessApi, type SourceLagView } from '@/services/freshnessApi'
import { formatAge, formatSeconds } from '@/lib/time'

/**
 * The sources behind their freshness window (#93, FR-6), so that a reader knows which answers may
 * no longer match reality before trusting them. A source within its window is not worth a line.
 *
 * Said in words, not only in colour. And quiet when lag cannot be read: this is a warning about the
 * page, never the reason the page fails.
 */
const sources = ref<SourceLagView[]>([])

const lagging = computed(() => sources.value.filter(source => source.lagging))

onMounted(() => {
  freshnessApi
    .sources()
    .then(answer => {
      sources.value = answer
    })
    .catch(() => {
      sources.value = []
    })
})
</script>

<template>
  <ul v-if="lagging.length > 0" data-test="source-lag" class="source-lag" role="status">
    <li v-for="source in lagging" :key="source.source" :data-test="`source-lag-${source.source}`">
      <strong>{{ source.source }}</strong>
      <template v-if="source.lagSeconds === null || source.lagSeconds === undefined">
        has never synced, so it is behind its freshness window of
        {{ formatSeconds(source.windowSeconds) }}
      </template>
      <template v-else>
        is {{ formatSeconds(source.lagSeconds) }} behind: last synced
        {{ formatAge(source.lagSeconds) }}, against a freshness window of
        {{ formatSeconds(source.windowSeconds) }}
      </template>
    </li>
  </ul>
</template>

<style scoped>
.source-lag {
  list-style: none;
  margin: 0 0 1rem;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
  background: #fffaf0;
  border: 1px solid #fbd38d;
  color: #744210;
  font-size: 0.85rem;
}
.source-lag li + li {
  margin-top: 0.25rem;
}
</style>
