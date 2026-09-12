import { expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import type { AxiomDescriptor } from '../../api/types'
import SemanticWorkbench from '../SemanticWorkbench.vue'

const rdfsLabel = 'http://www.w3.org/2000/01/rdf-schema#label'
const xsdString = 'http://www.w3.org/2001/XMLSchema#string'
const axiom = (id: string, rendering: string, signatureIris: string[]): AxiomDescriptor => ({
  axiomId: id, axiomType: rendering.split('(')[0]!, rendering, signatureIris, annotations: [], logical: true,
})
const axioms = [
  axiom('line', 'Declaration(Class(<urn:test:Line>))', ['urn:test:Line']),
  axiom('factory', 'Declaration(Class(<urn:test:Factory>))', ['urn:test:Factory']),
  axiom('equipment', 'Declaration(Class(<urn:test:Equipment>))', ['urn:test:Equipment']),
  axiom('relation', 'Declaration(ObjectProperty(<urn:test:hasEquipment>))', ['urn:test:hasEquipment']),
  axiom('serial', 'Declaration(DataProperty(<urn:test:serial>))', ['urn:test:serial']),
  axiom('string', `Declaration(Datatype(<${xsdString}>))`, [xsdString]),
  axiom('hash', 'Declaration(NamedIndividual(<urn:test:individual#8f31>))', ['urn:test:individual#8f31']),
  axiom('line-label', `AnnotationAssertion(<${rdfsLabel}> <urn:test:Line> "产线")`, [rdfsLabel, 'urn:test:Line']),
  axiom('factory-label', `AnnotationAssertion(<${rdfsLabel}> <urn:test:Factory> "工厂")`, [rdfsLabel, 'urn:test:Factory']),
  axiom('equipment-label', `AnnotationAssertion(<${rdfsLabel}> <urn:test:Equipment> "设备")`, [rdfsLabel, 'urn:test:Equipment']),
]

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { graphId: 'graph' }, query: {} }) }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({
  workspace: { currentWorkspaceId: 'ws', accessLoaded: true, isGlobalAdmin: true },
  begin: () => ({ id: 'ws', current: () => true, signal: new AbortController().signal }),
}) }))
vi.mock('../../api/graphApi', () => ({ graphApi: {
  entities: async () => ({ items: [{ id: 'entity-1', graphId: 'graph', iri: 'urn:test:individual#P-101', assertedTypes: ['urn:test:Equipment', 'urn:test:Unknown'], displayName: 'P-101', status: 'ACTIVE', createdAt: '' }] }),
} }))
vi.mock('../../api/graphQueryApi', () => ({ graphQueryApi: {
  detail: async () => ({ binding: { enabled: true, ontologyVersion: 1, graphVersion: 1, knowledgeBaseId: 'kb' }, document: { source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: '', imports: [], policy: { version: '1', rules: [] } }, ontologyIri: 'urn:test:plant', versionIri: null, documentDigest: 'sha256:doc', importLockDigest: 'sha256:imports', axioms } }),
  neighbors: async () => ({ nodes: [], edges: [], traceId: '', truncated: false }),
} }))
vi.mock('../../api/sourceApi', () => ({ sourceApi: { sources: async () => ({ items: [] }), snapshots: async () => ({ items: [] }) } }))
vi.mock('../../api/statementApi', () => ({ statementApi: { list: async () => ({ items: [], total: 0 }), changes: async () => ({ items: [], total: 0 }), conflicts: async () => ({ items: [], total: 0 }) } }))
vi.mock('../EntityEditor.vue', () => ({ default: {
  props: ['classOptions'],
  template: '<div data-entity-editor>{{ classOptions.map(option => `${option.label}:${option.iri}`).join("|") }}</div>',
} }))
vi.mock('../SemanticGraphView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../GraphMigrationPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../ModelSampleReview.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../ExtractionWizard.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../SourceCapturePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/SourceGovernancePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/SourceChangeReviewPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/StatementReviewDrawer.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/EvidenceDrawer.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../review/ConflictReviewPanel.vue', () => ({ default: { template: '<div />' } }))

it('offers only labeled class terms as entity types and labels existing entity types', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(SemanticWorkbench)
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
  try {
    await flush()
    ;(host.querySelector('#tab-entities') as HTMLElement).click()
    await flush()
    expect(host.querySelector('[data-entity-editor]')?.textContent).toBe('产线:urn:test:Line|工厂:urn:test:Factory|设备:urn:test:Equipment')
    const entityTable = host.querySelectorAll('.el-table')[1]
    expect(entityTable?.textContent).toContain('设备')
    expect(entityTable?.textContent).toContain('urn:test:Unknown')
    expect(host.textContent).not.toContain('关系属性')
    expect(host.textContent).not.toContain(xsdString)
    expect(host.textContent).not.toContain('urn:test:individual#8f31')
  } finally { app.unmount(); host.remove() }
})
