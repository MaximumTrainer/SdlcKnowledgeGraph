<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { infoApi, ontologyApi, type DeploymentInfo, type OntologyNodeType } from '@/services/api'
import { AUTH_SESSION } from '@/auth/session'
import AccessDeniedBanner from '@/components/AccessDeniedBanner.vue'

/**
 * The frame around every page: a header whose node-type navigation comes from the ontology, so a
 * type added to the registry is browsable without a frontend release, and a footer that says which
 * ontology and build are serving (#6).
 *
 * Meta types (the graph's own bookkeeping, such as sync runs) are left out of the navigation.
 */
// Null when the deployment has no login (#114); the header then names no user.
const session = inject(AUTH_SESSION, null)
const username = computed(() => session?.username.value ?? null)

const nodeTypes = ref<OntologyNodeType[] | null>(null)
const ontologyVersion = ref<string | null>(null)
const ontologyFailed = ref(false)
const deployment = ref<DeploymentInfo['deployment'] | null>(null)

const browsable = computed(() => (nodeTypes.value ?? []).filter(type => !type.meta))

// The short commit, as git prints it. "unknown" when the image was not stamped with one, or when the
// info endpoint cannot be read: the footer must never be what stops a page from rendering.
const build = computed(() => {
  const commit = deployment.value?.commit
  return commit && /^[0-9a-f]{40}$/.test(commit) ? commit.slice(0, 7) : 'unknown'
})

onMounted(() => {
  ontologyApi
    .get()
    .then(ontology => {
      nodeTypes.value = ontology.nodeTypes
      ontologyVersion.value = ontology.version
    })
    .catch(() => {
      ontologyFailed.value = true
    })
  infoApi
    .get()
    .then(info => {
      deployment.value = info.deployment
    })
    .catch(() => {
      deployment.value = null
    })
})
</script>

<template>
  <div class="app-layout">
    <header class="app-header">
      <div class="app-header-row">
        <router-link to="/" class="brand">🕸 RepoDataGraph</router-link>
        <div class="header-links">
          <router-link to="/graph" class="header-link">Graph</router-link>
          <router-link to="/connectors" class="header-link">Connectors</router-link>
          <router-link to="/sync-runs" class="header-link">Sync runs</router-link>
          <router-link to="/links/candidates" class="header-link">Links</router-link>
          <router-link to="/admin/lifecycle" class="header-link">Lifecycle</router-link>
          <router-link to="/admin/policy" class="header-link">Policy</router-link>
          <template v-if="username">
            <span class="header-user" data-test="signed-in-user">{{ username }}</span>
            <button
              type="button"
              class="header-link sign-out"
              data-test="sign-out"
              @click="session?.signOut()"
            >
              Sign out
            </button>
          </template>
        </div>
      </div>
      <nav
        aria-label="Node types"
        class="type-nav"
        :aria-busy="nodeTypes === null && !ontologyFailed ? 'true' : 'false'"
      >
        <p v-if="ontologyFailed" role="alert" class="nav-error">
          Could not load the node types. Reload to try again.
        </p>
        <span v-else-if="nodeTypes === null" class="nav-skeleton">Loading node types…</span>
        <router-link v-for="type in browsable" v-else :key="type.name" :to="`/nodes/${type.name}`">
          {{ type.name }}
        </router-link>
      </nav>
    </header>
    <main class="app-main">
      <AccessDeniedBanner />
      <slot />
    </main>
    <footer class="app-footer">
      <span v-if="ontologyVersion">ontology v{{ ontologyVersion }}</span>
      <span>build {{ build }}</span>
      <span v-if="deployment?.readOnly">read-only</span>
    </footer>
  </div>
</template>

<style scoped>
.app-header {
  background: #1a202c;
  padding: 0 2rem;
}
.app-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
}
.brand {
  color: #63b3ed;
  text-decoration: none;
  font-size: 1.25rem;
  font-weight: 700;
}
.header-links {
  display: flex;
  gap: 1.25rem;
}
.header-link,
.type-nav a {
  color: #a0aec0;
  text-decoration: none;
  font-size: 0.9rem;
}
.header-user {
  color: #e2e8f0;
  font-size: 0.9rem;
}
.sign-out {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}
.header-link:hover,
.header-link.router-link-active,
.type-nav a:hover,
.type-nav a.router-link-active {
  color: #fff;
}
.type-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1.25rem;
  padding: 0 0 0.75rem;
}
.nav-skeleton {
  color: #718096;
  font-size: 0.9rem;
}
.nav-error {
  color: #feb2b2;
  font-size: 0.9rem;
}
.app-main {
  max-width: 1100px;
  margin: 0 auto;
  padding: 2rem;
}
.app-footer {
  display: flex;
  justify-content: center;
  gap: 1.5rem;
  padding: 1.5rem 2rem;
  color: #718096;
  font-size: 0.8rem;
}
</style>
