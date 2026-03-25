import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import RepositoryList from './views/RepositoryList.vue'
import RepositoryDetail from './views/RepositoryDetail.vue'
import RepositoryEditor from './views/RepositoryEditor.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: RepositoryList },
    { path: '/repositories/new', component: RepositoryEditor },
    { path: '/repositories/:id', component: RepositoryDetail },
    { path: '/repositories/:id/edit', component: RepositoryEditor, props: true }
  ]
})

createApp(App).use(router).mount('#app')
