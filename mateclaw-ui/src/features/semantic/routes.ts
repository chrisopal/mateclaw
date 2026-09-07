import type { RouteRecordRaw } from 'vue-router'
export const semanticRoutes: RouteRecordRaw[] = [
  { path: 'semantic/graphs/:graphId', name: 'SemanticWorkbench', component: () => import('./graph/SemanticWorkbench.vue'), meta: { requiredCapability: 'view:ontology', semantic: true } },
  {
    path: 'semantic/ontologies',
    name: 'OntologyList',
    component: () => import('./ontology/OntologyList.vue'),
    meta: { requiredCapability: 'view:ontology', semantic: true },
  },
  {
    path: 'semantic/ontologies/:id/edit',
    name: 'OntologyEditor',
    component: () => import('./ontology/OntologyEditor.vue'),
    meta: { requiredCapability: 'view:ontology', semantic: true },
  },
  {
    path: 'semantic/ontologies/:id/versions',
    name: 'OntologyVersions',
    component: () => import('./ontology/OntologyVersions.vue'),
    meta: { requiredCapability: 'view:ontology', semantic: true },
  },
]
