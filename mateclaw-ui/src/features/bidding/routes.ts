import type { RouteRecordRaw } from 'vue-router'

export const biddingRoutes: RouteRecordRaw[] = [
  { path: 'bidding', name: 'BiddingProjects', component: () => import('./pages/BiddingProjects.vue'), meta: { title: 'Bidding' } },
  { path: 'bidding/:id', name: 'BiddingWorkbench', component: () => import('./pages/BiddingWorkbench.vue'), meta: { title: 'Bidding' } },
]
