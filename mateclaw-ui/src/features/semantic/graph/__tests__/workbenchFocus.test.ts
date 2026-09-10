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
vi.mock('../../api/graphQueryApi', () => ({ graphQueryApi: { detail: async () => ({ binding: { enabled: false, ontologyVersion: 1, graphVersion: 1 }, document: { source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: '', imports: [], policy: { version: '1', rules: [] } }, ontologyIri: 'https://example.test/plant', versionIri: null, documentDigest: 'sha256:doc', importLockDigest: 'sha256:imports', axioms: [] } }) } }))
vi.mock('../../api/sourceApi', () => ({ sourceApi: { sources: async () => ({ items: [] }), snapshots: async () => ({ items: [] }) } }))
vi.mock('../../api/statementApi', () => ({ statementApi: { list: async (_ws: string, _graph: string, _view: string) => ({ items: [{ id: 'fact-1', graphId: 'graph', revision: 1, ontologyRevisionId: 'revision', subjectId: 'entity-1', assertion: { kind: 'POSITIVE_DATA_PROPERTY', functionalSyntax: 'DataPropertyAssertion(<https://example.test/deviation> <https://example.test/entity-1> "0.08"^^xsd:decimal)', signatureIris: ['https://example.test/entity-1', 'https://example.test/deviation'], subjectIri: 'https://example.test/entity-1', predicateIri: 'https://example.test/deviation', literal: { lexicalValue: '0.08', datatypeIri: 'http://www.w3.org/2001/XMLSchema#decimal' } }, validityKind: 'UNKNOWN', validFrom: null, validTo: null, reviewStatus: 'ACCEPTED', supportStatus: 'SUPPORTED', evidenceIds: [], proposedBy: 'u', createdAt: '' }], total: 1 }), changes: async () => ({ items: [], total: 0 }), conflicts: async () => ({ items: [], total: 0 }) } }))
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
