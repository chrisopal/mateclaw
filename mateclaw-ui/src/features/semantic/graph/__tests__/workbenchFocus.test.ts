import { expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import SemanticWorkbench from '../SemanticWorkbench.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { graphId: 'graph' }, query: { focus: 'fact-1', kind: 'STATEMENT' } }) }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => {
  let generation = 0
  return { workspace: { currentWorkspaceId: 'ws', accessLoaded: true, isGlobalAdmin: true }, begin: () => {
    const run = ++generation
    return { id: 'ws', current: () => run === generation, signal: new AbortController().signal }
  } }
} }))
vi.mock('../../api/graphApi', () => ({ graphApi: { entities: async () => ({ items: [] }) } }))
vi.mock('../../api/graphQueryApi', () => ({ graphQueryApi: { detail: async () => ({ binding: { enabled: false, ontologyVersion: 1, graphVersion: 1 }, definition: { types: [], properties: [], relations: [] } }) } }))
vi.mock('../../api/sourceApi', () => ({ sourceApi: { sources: async () => ({ items: [] }), snapshots: async () => ({ items: [] }) } }))
vi.mock('../../api/statementApi', () => ({ statementApi: { list: async (_ws: string, _graph: string, _view: string) => ({ items: [{ id: 'fact-1', subjectId: 'entity-1', predicateKind: 'PROPERTY', predicateKey: 'deviation', value: '0.08', unit: 'mm', reviewStatus: 'ACCEPTED', evidenceIds: [] }], total: 1 }), changes: async () => ({ items: [], total: 0 }), conflicts: async () => ({ items: [], total: 0 }) } }))
vi.mock('../SemanticGraphView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/StatementReviewDrawer.vue', () => ({ default: { props: ['statement'], template: '<div data-focused>{{ statement?.id }}</div>' } }))
vi.mock('../../review/EvidenceDrawer.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/SourceGovernancePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/ConflictReviewPanel.vue', () => ({ default: { template: '<div />' } }))

it('waits for initial graph load before resolving a fact focus', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(SemanticWorkbench)
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  try {
    await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
    expect(host.textContent).toContain('0.08')
    expect(host.querySelector('[data-focused]')?.textContent).toBe('fact-1')
    expect(host.querySelector('header button')?.hasAttribute('disabled')).toBe(false)
  } finally { app.unmount(); host.remove() }
})
