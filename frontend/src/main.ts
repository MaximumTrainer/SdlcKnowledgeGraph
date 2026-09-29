import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import NodeList from './views/NodeList.vue'
import NodeDetail from './views/NodeDetail.vue'
import NodeEditor from './views/NodeEditor.vue'
import ConnectorsView from './views/ConnectorsView.vue'
import SyncRunsView from './views/SyncRunsView.vue'
import NotFound from './views/NotFound.vue'

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
    { path: '/connectors', component: ConnectorsView },
    { path: '/sync-runs', component: SyncRunsView },
    { path: '/repositories', redirect: '/nodes/Repository' },
    { path: '/repositories/new', redirect: '/nodes/Repository/new' },
    { path: '/repositories/:id', redirect: to => `/nodes/Repository/${to.params.id}` },
    // Last, so every route above wins. nginx serves index.html for any path, so this is the page a
    // mistyped or stale link actually reaches.
    { path: '/:pathMatch(.*)*', component: NotFound }
  ]
})

createApp(App).use(router).mount('#app')
