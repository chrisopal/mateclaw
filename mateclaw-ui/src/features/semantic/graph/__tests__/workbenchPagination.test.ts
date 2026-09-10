import { expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import SemanticWorkbench from '../SemanticWorkbench.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { graphId: 'graph' } }) }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({ workspace: { currentWorkspaceId: 'ws', accessLoaded: true, isGlobalAdmin: true }, begin: () => ({ id: 'ws', current: () => true, signal: new AbortController().signal }) }) }))
vi.mock('../../api/graphApi', () => ({ graphApi: { entities: async () => ({ items: [] }) } }))
vi.mock('../../api/graphQueryApi', () => ({ graphQueryApi: { detail: async () => ({ binding: { enabled: false, ontologyVersion: 1, graphVersion: 1 }, document: { source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: '', imports: [], policy: { version: '1', rules: [] } }, ontologyIri: 'https://example.test/plant', versionIri: null, documentDigest: 'sha256:doc', importLockDigest: 'sha256:imports', axioms: [] } }) } }))
vi.mock('../../api/sourceApi', () => ({ sourceApi: { sources: async () => ({ items: [] }), snapshots: async () => ({ items: [] }) } }))
vi.mock('../../api/statementApi', () => ({ statementApi: { list: async (_ws: string, _graph: string, view: string) => ({ items: [], total: view === 'trusted' ? 1 : 201 }), changes: async () => ({ items: [], total: 0 }), conflicts: async () => ({ items: [], total: 0 }) } }))
vi.mock('../SemanticGraphView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/StatementReviewDrawer.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/EvidenceDrawer.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/SourceGovernancePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/ConflictReviewPanel.vue', () => ({ default: { template: '<div />' } }))

it('uses the active queue total and hides pagination on non-list tabs', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(SemanticWorkbench)
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
  await flush()
  try {
    expect(host.querySelector('.el-pagination')).toBeNull()
    ;(host.querySelector('#tab-queue') as HTMLElement).click()
    await flush()
    expect(host.querySelector('.el-pagination')).not.toBeNull()
    expect(host.querySelectorAll('.el-pager .number')).toHaveLength(3)
    ;(host.querySelector('#tab-sources') as HTMLElement).click()
    await flush()
    expect(host.querySelector('.el-pagination')).toBeNull()
  } finally { app.unmount(); host.remove() }
})
