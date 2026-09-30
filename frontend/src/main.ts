import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import NodeList from './views/NodeList.vue'
import NodeDetail from './views/NodeDetail.vue'
import NodeEditor from './views/NodeEditor.vue'
import ConnectorsView from './views/ConnectorsView.vue'
import SyncRunsView from './views/SyncRunsView.vue'
import LifecycleView from './views/LifecycleView.vue'
import NotFound from './views/NotFound.vue'
import AuthCallback from './views/AuthCallback.vue'
import { apiClient } from './services/api'
import { loadAuthConfig } from './auth/config'
import { AUTH_SESSION, CALLBACK_PATH, createAuthSession } from './auth/session'
import { attachAuth } from './auth/http'
import { guardRoutes } from './auth/guard'

/**
 * One set of routes for every node type, keyed by registry type, so a type added to the ontology is
 * reachable without a frontend release.
 *
 * A node's key contains slashes (`github.com/acme/payments`), so the id segment is a repeated
 * parameter; `props` flattens it back into the string the API expects. `/repositories/*` is kept as a
 * redirect because links to it exist.
 */
const nodeId = (segments: string | string[]) =>
  Array.isArray(segments) ? segments.join('/') : segments

const GraphView = () => import('./views/GraphView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/nodes/Repository' },
    { path: '/nodes/:type', component: NodeList, props: true },
    { path: '/nodes/:type/new', component: NodeEditor, props: true },
    {
      path: '/nodes/:type/:id+/edit',
      component: NodeEditor,
      props: route => ({ type: route.params.type, id: nodeId(route.params.id) })
    },
    {
      path: '/nodes/:type/:id+',
      component: NodeDetail,
      props: route => ({ type: route.params.type, id: nodeId(route.params.id) })
    },
    // The graph view (#9). A node id is `Type:key` and a key has slashes, like the routes above.
    // Loaded when first visited, so Cytoscape is not in the bundle every other page waits for.
    { path: '/graph', component: GraphView },
    {
      path: '/graph/:nodeId+',
      name: 'graph',
      component: GraphView,
      props: route => ({ nodeId: nodeId(route.params.nodeId) })
    },
    { path: '/connectors', component: ConnectorsView },
    { path: '/sync-runs', component: SyncRunsView },
    // The data lifecycle's administration (#33): migrations, the archive, each connector's rules.
    { path: '/admin/lifecycle', component: LifecycleView },
    { path: CALLBACK_PATH, component: AuthCallback },
    { path: '/repositories', redirect: '/nodes/Repository' },
    { path: '/repositories/new', redirect: '/nodes/Repository/new' },
    { path: '/repositories/:id', redirect: to => `/nodes/Repository/${to.params.id}` },
    // Last, so every route above wins. nginx serves index.html for any path, so this is the page a
    // mistyped or stale link actually reaches.
    { path: '/:pathMatch(.*)*', component: NotFound }
  ]
})

/**
 * Whether users sign in is the deployment's decision (#114, #118): with an identity provider
 * configured, every page waits for a signed-in user and every API call carries their token; without
 * one, the API is running its anonymous read-only mode, and the web interface reads the graph without
 * a login and offers no way to change it.
 */
loadAuthConfig().then(config => {
  const session = config ? createAuthSession(config) : null
  if (session) {
    attachAuth(apiClient, session)
    guardRoutes(router, session)
  }
  createApp(App).provide(AUTH_SESSION, session).use(router).mount('#app')
})
