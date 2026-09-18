import type { RouteRecordRaw } from 'vue-router'
export const presalesRoutes: RouteRecordRaw[] = [
  { path: 'presales', name: 'PresalesProjects', component: () => import('./pages/PresalesWorkbench.vue'), meta: { title: 'Presales' } },
  { path: 'presales/:projectId', name: 'PresalesProject', component: () => import('./pages/PresalesWorkbench.vue'), meta: { title: 'Presales' } },
]
