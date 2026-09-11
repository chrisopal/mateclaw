import { createApp, h, nextTick, reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BusinessPolicyPanel from '../components/BusinessPolicyPanel.vue'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import type { Draft } from '../../api/types'
import type { DisplayProjection } from '../standardProjection'

const api = vi.hoisted(() => ({ saveBusinessPolicy: vi.fn(), checkBusinessPolicySample: vi.fn() }))
vi.mock('../../api/ontologyApi', () => ({ ontologyApi: api }))

const projection: DisplayProjection = {
  schemaVersion: 'ontology-display-v1', documentDigest: 'digest', importLockDigest: 'imports',
  nodes: [
    { id: 'class:urn:equipment', iri: 'urn:equipment', kind: 'class' as const, labels: [{ value: '生产设备', language: 'zh-CN', axiomId: 'class' }], axiomIds: [], features: [], imported: false },
    { id: 'dataProperty:urn:serial', iri: 'urn:serial', kind: 'dataProperty' as const, labels: [{ value: '设备编号', language: 'zh-CN', axiomId: 'serial' }], axiomIds: [], features: [], imported: false },
    { id: 'datatype:http://www.w3.org/2001/XMLSchema#string', iri: 'http://www.w3.org/2001/XMLSchema#string', kind: 'datatype' as const, labels: [], axiomIds: [], features: [], imported: false },
  ],
  edges: [
    { id: 'domain', source: 'class:urn:equipment', target: 'dataProperty:urn:serial', kind: 'domain' as const, label: '', axiomId: 'domain' },
    { id: 'range', source: 'dataProperty:urn:serial', target: 'datatype:http://www.w3.org/2001/XMLSchema#string', kind: 'range' as const, label: '', axiomId: 'range' },
  ], axiomRefs: [], coverage: { total: 3, returned: 3, truncated: false, dependencyScope: 'ROOT', lockedImportCount: 0 },
}
const policy = () => ({ version: 'policy-1', rules: [{ classIri: 'urn:equipment', predicateIri: 'urn:serial', required: true, unit: null, allowedLexicalValues: [], singleValue: true }] })
const draft = (): Draft => ({ id: 'draft', ontologyId: 'ontology', baseRevisionId: null, version: 1, draftVersion: 8, name: 'Factory', description: '', document: { source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: 'Ontology(<urn:factory>)', imports: [], policy: policy() }, ontologyIri: 'urn:factory', versionIri: null, documentDigest: 'digest', importLockDigest: 'imports', axioms: [] } })
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
let app: ReturnType<typeof createApp>, host: HTMLDivElement

function mount() {
  const pinia = createPinia(); setActivePinia(pinia); const workspace = useWorkspaceStore(); workspace.currentWorkspaceId = 'workspace'; workspace.accessLoaded = true; workspace.currentCapabilities = new Set(['manage:ontology'] as never)
  const props = reactive({ ontologyId: 'ontology', draftVersion: 8, policy: policy(), projection, editable: true, disabled: false })
  const saved: Draft[] = []; const pending: boolean[] = []
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ setup: () => () => h(BusinessPolicyPanel, { ...props, onSaved: (value: Draft) => saved.push(value), onPendingChange: (value: boolean) => pending.push(value) }) })
  app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: {} })).mount(host)
  return { props, saved, pending }
}
beforeEach(() => { vi.clearAllMocks(); api.saveBusinessPolicy.mockResolvedValue(draft()); api.checkBusinessPolicySample.mockResolvedValue({ draftVersion: 8, policyVersion: 'policy-1', valid: true, violations: [] }) })
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('business policy authoring', () => {
  it('renders friendly property context and sends the complete policy with a stable operation id', async () => {
    const { saved } = mount(); await flush()
    expect(host.textContent).toContain('业务规则'); expect(host.textContent).toContain('设备编号'); expect(host.textContent).toContain('文本')
    ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('编辑'))!.click(); await flush()
    expect(document.body.textContent).toContain('应用规则')
    ;[...document.querySelectorAll('button')].find(button => button.textContent?.includes('应用规则'))!.click(); await flush()
    const save = [...host.querySelectorAll('button')].find(button => button.textContent?.includes('保存业务规则'))!
    save.click(); await flush()
    expect(api.saveBusinessPolicy).toHaveBeenCalledOnce()
    const firstBody = api.saveBusinessPolicy.mock.calls[0][2]
    expect(firstBody).toMatchObject({ expectedDraftVersion: 8, rules: [expect.objectContaining({ classIri: 'urn:equipment', predicateIri: 'urn:serial', required: true, singleValue: true })] })
    expect(firstBody.operationId).toBeTruthy(); expect(saved).toHaveLength(1)
  })

  it('serializes newline sample values with the projected datatype and drops a late result after input changes', async () => {
    mount(); await flush()
    const textarea = host.querySelector('textarea')!; textarea.value = 'EQ-01\nEQ-02'; textarea.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    let resolve!: (value: unknown) => void
    api.checkBusinessPolicySample.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('检查样例'))!.click(); await flush()
    expect(api.checkBusinessPolicySample.mock.calls[0][2]).toMatchObject({ expectedDraftVersion: 8, classIri: 'urn:equipment', completeSubmission: true, properties: { 'urn:serial': [{ lexicalValue: 'EQ-01', datatypeIri: 'http://www.w3.org/2001/XMLSchema#string', unit: null }, { lexicalValue: 'EQ-02', datatypeIri: 'http://www.w3.org/2001/XMLSchema#string', unit: null }] } })
    textarea.value = 'EQ-03'; textarea.dispatchEvent(new Event('input', { bubbles: true })); await nextTick(); resolve({ draftVersion: 8, policyVersion: 'policy-1', valid: true, violations: [] }); await flush()
    expect(host.textContent).not.toContain('符合业务规则')
  })

  it('keeps an ambiguous save operation body for explicit recovery', async () => {
    const { pending } = mount(); await flush(); api.saveBusinessPolicy.mockRejectedValueOnce({ status: 0, code: 'REQUEST_FAILED', message: 'offline' })
    ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('保存业务规则'))!.click(); await flush()
    expect(pending.at(-1)).toBe(true); expect(host.textContent).toContain('恢复保存')
    const original = api.saveBusinessPolicy.mock.calls[0][2]; api.saveBusinessPolicy.mockResolvedValueOnce(draft())
    ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('恢复保存'))!.click(); await flush()
    expect(api.saveBusinessPolicy.mock.calls[1][2]).toEqual(original); expect(pending.at(-1)).toBe(false)
  })
  it('disables sample checking for changed rules and restores them on discard', async () => {
    mount(); await flush()
    ;[...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '编辑')!.click(); await flush()
    const unit = document.querySelector<HTMLInputElement>('.el-dialog input[placeholder*="kW"]')!
    unit.value = 'kW'; unit.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    ;[...document.querySelectorAll('button')].find(b => b.textContent?.includes('应用规则'))!.click(); await flush()
    const check = [...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '检查样例')!
    expect(check.disabled).toBe(true)
    ;[...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '放弃修改')!.click(); await flush()
    expect(check.disabled).toBe(false)
    expect(host.querySelector('.policy-rule-row')?.textContent).not.toContain('kW')
  })

  it('uses the sample class properties independently from the rule editor class', async () => {
    const { props } = mount(); await flush()
    props.projection = { ...projection, nodes: [...projection.nodes, { ...projection.nodes[0]!, id: 'class:urn:other', iri: 'urn:other', labels: [{ value: '其他对象', language: 'zh-CN', axiomId: 'other' }] }] }
    await flush()
    host.querySelectorAll<HTMLElement>('.el-select__wrapper')[1]!.click(); await flush()
    ;[...document.querySelectorAll<HTMLElement>('[role="option"]')].filter(e => e.textContent?.trim() === '其他对象').at(-1)!.click(); await flush()
    ;[...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '检查样例')!.click(); await flush()
    expect(api.checkBusinessPolicySample.mock.calls[0][2]).toMatchObject({ classIri: 'urn:other', properties: {} })
    expect(host.querySelectorAll('.sample-property-field')).toHaveLength(0)
    expect(host.querySelector('.policy-rule-row')?.textContent).toContain('设备编号')
  })

})
