import { createApp, h, nextTick, reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { describe, expect, it, beforeEach, vi } from 'vitest'
import zh from '@/i18n/locales/zh-CN'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import type { Definition, OntologyPackage, Property } from '../../api/types'
import OntologyImpactPanel from '../components/OntologyImpactPanel.vue'
import OntologyPackageDialog from '../components/OntologyPackageDialog.vue'
import PropertyEditor from '../components/PropertyEditor.vue'

const api = vi.hoisted(() => ({
  usage: vi.fn(),
  impact: vi.fn(),
  previewPackage: vi.fn(),
  importPackage: vi.fn(),
  packageImport: vi.fn(),
}))
const push = vi.hoisted(() => vi.fn())

vi.mock('../../api/ontologyApi', () => ({ ontologyApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const definition: Definition = { definitionFormatVersion: 2, types: [{ key: 'Pump', label: '泵', description: '' }], properties: [], relations: [] }
const packageData: OntologyPackage = { packageFormatVersion: 1, name: '质量本体', description: '', source: { ontologyName: '质量本体', version: 1 }, definition }
const report = {
  graphId: 'graph-1', sourceRevisionId: 'revision-1', targetRevisionId: 'revision-2', definitionDigest: 'sha256:test', graphVersion: 4,
  scannedAt: '2026-09-08T01:02:03Z', definitionChangeClass: 'POTENTIALLY_BREAKING', dataConformance: 'VIOLATIONS',
  scannedEntities: 2, scannedStatements: 3, scannedProposals: 1, affectedEntities: 1, affectedStatements: 1, affectedProposals: 1,
  detailsTruncated: false, diagnostics: [{ kind: 'ENTITY' as const, id: 'entity-1', code: 'UNKNOWN_ENTITY_TYPE', message: 'missing type' }],
}
const flush = async () => { await new Promise((resolve) => setTimeout(resolve, 0)); await nextTick() }
function installWorkspace() {
  setActivePinia(createPinia())
  const workspace = useWorkspaceStore()
  workspace.currentWorkspaceId = 'workspace-1'
  workspace.accessLoaded = true
  workspace.currentCapabilities = new Set(['publish:ontology', 'view:ontology'] as never)
  return workspace
}
function mountApp(component: any, props: Record<string, unknown> = {}) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(component, props)
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  return { app, host }
}

describe('M4 impact and package flows', () => {
  beforeEach(() => {
    installWorkspace()
    vi.clearAllMocks()
    api.usage.mockResolvedValue({ items: [{ graphId: 'graph-1', kbId: 'kb-1', kbName: '工厂知识库', ontologyRevisionId: 'revision-1', ontologyVersion: 1, enabled: true, graphVersion: 4 }], total: 1, page: 1, pageSize: 20 })
    api.impact.mockResolvedValue(report)
    api.previewPackage.mockResolvedValue({ digest: 'sha256:test', name: '质量本体', typeCount: 1, predicateCount: 0, violations: [] })
    api.importPackage.mockResolvedValue({ operationId: 'op-1', ontologyId: 'ontology-1', draft: { id: 'draft-1' } })
    api.packageImport.mockResolvedValue({ operationId: 'op-1', ontologyId: 'ontology-1', draft: { id: 'draft-1' } })
  })

  it('cancels and clears a usage request when impact becomes disabled', async () => {
    let signal: AbortSignal | undefined
    api.usage.mockImplementation((_scope: string, _id: string, _page: number, _size: number, requestSignal: AbortSignal) => {
      signal = requestSignal
      return new Promise(() => {})
    })
    const state = reactive({ enabled: true })
    const { app, host } = mountApp({ setup: () => () => h(OntologyImpactPanel, { ontologyId: 'ontology-1', targetRevisionId: 'revision-2', enabled: state.enabled }) })
    await flush()
    state.enabled = false
    await nextTick()
    await flush()
    expect(signal?.aborted).toBe(true)
    expect(host.textContent).toContain('暂无知识图谱使用此本体')
    app.unmount(); host.remove()
  })

  it('shows scan freshness and routes a diagnostic to the graph workbench', async () => {
    const { app, host } = mountApp(OntologyImpactPanel, { ontologyId: 'ontology-1', targetRevisionId: 'revision-2', enabled: true })
    await flush()
    const analyzeButton = Array.from(host.querySelectorAll('button')).find((button) => button.textContent?.includes('分析影响')) as HTMLButtonElement
    analyzeButton?.click()
    await flush()
    expect(host.textContent).toContain('扫描时间')
    expect(host.textContent).toContain('图版本')
    expect(host.textContent).toContain('实体')
    expect(host.textContent).toContain('目标本体缺少实体类型')
    const locateButton = Array.from(host.querySelectorAll('button')).find((button) => button.textContent?.includes('定位')) as HTMLButtonElement
    locateButton?.click()
    expect(push).toHaveBeenCalledWith({ name: 'SemanticWorkbench', params: { graphId: 'graph-1' }, query: { focus: 'entity-1', kind: 'ENTITY' } })
    app.unmount(); host.remove()
  })

  it('removes a previous conforming report when a fresh analysis fails', async () => {
    api.impact.mockResolvedValueOnce({ ...report, dataConformance: 'CONFORMS', diagnostics: [] })
    const { app, host } = mountApp(OntologyImpactPanel, { ontologyId: 'ontology-1', targetRevisionId: 'revision-2', enabled: true })
    await flush()
    const analyze = () => Array.from(host.querySelectorAll('button')).find((button) => button.textContent?.includes('分析影响'))?.click()
    analyze(); await flush()
    expect(host.querySelector('.semantic-impact-report')).not.toBeNull()
    api.impact.mockRejectedValueOnce({ status: 504, code: 'IMPACT_INCOMPLETE', message: 'budget' })
    analyze(); await flush()
    expect(host.querySelector('.semantic-impact-report')).toBeNull()
    expect(host.textContent).toContain('budget')
    app.unmount(); host.remove()
  })

  it('resets an open package dialog on workspace switch and aborts preview', async () => {
    const workspace = useWorkspaceStore()
    let signal: AbortSignal | undefined
    api.previewPackage.mockImplementation((_scope: string, _body: OntologyPackage, requestSignal: AbortSignal) => {
      signal = requestSignal
      return new Promise(() => {})
    })
    const state = reactive({ open: true })
    const { app, host } = mountApp({ setup: () => () => h(OntologyPackageDialog, { modelValue: state.open, 'onUpdate:modelValue': (value: boolean) => { state.open = value } }) })
    await flush()
    const fileInput = document.body.querySelector('input[type=file]') as HTMLInputElement
    const file = new File([JSON.stringify(packageData)], 'quality.json', { type: 'application/json' })
    Object.defineProperty(fileInput, 'files', { value: [file] })
    fileInput.dispatchEvent(new Event('change'))
    await flush()
    Array.from(document.body.querySelectorAll('button')).find((button) => button.textContent?.includes('预览'))?.click()
    await flush()
    workspace.currentWorkspaceId = 'workspace-2'
    await flush()
    expect(signal?.aborted).toBe(true)
    expect(api.previewPackage.mock.calls[0]?.[1]).toBe(JSON.stringify(packageData))
    expect(document.body.querySelector('.semantic-package-preview')).toBeNull()
    app.unmount(); host.remove()
  })

  it('keeps permission failures visible and recovers an uncertain import', async () => {
    const state = reactive({ open: true })
    let created: unknown
    api.importPackage.mockRejectedValueOnce({ status: 403, code: 'FORBIDDEN', message: 'denied' })
    const { app, host } = mountApp({ setup: () => () => h(OntologyPackageDialog, {
      modelValue: state.open,
      'onUpdate:modelValue': (value: boolean) => { state.open = value },
      onCreated: (value: unknown) => { created = value },
    }) })
    await flush()
    const fileInput = document.body.querySelector('input[type=file]') as HTMLInputElement
    const file = new File([JSON.stringify(packageData)], 'quality.json', { type: 'application/json' })
    Object.defineProperty(fileInput, 'files', { value: [file] })
    fileInput.dispatchEvent(new Event('change'))
    await flush()
    Array.from(document.body.querySelectorAll('button')).find((button) => button.textContent?.includes('预览'))?.click()
    await flush()
    Array.from(document.body.querySelectorAll('button')).find((button) => button.textContent?.includes('创建草稿'))?.click()
    await flush()
    expect(document.body.textContent).toContain('当前权限不允许此操作')
    expect(document.body.textContent).not.toContain('恢复导入')

    api.importPackage.mockRejectedValueOnce({ status: 0, code: 'REQUEST_FAILED', message: 'network' })
    Array.from(document.body.querySelectorAll('button')).find((button) => button.textContent?.includes('创建草稿'))?.click()
    await flush()
    expect(document.body.textContent).toContain('恢复导入')
    Array.from(document.body.querySelectorAll('button')).find((button) => button.textContent?.includes('恢复导入'))?.click()
    await flush()
    expect(api.packageImport).toHaveBeenCalledWith('workspace-1', expect.any(String), expect.any(AbortSignal))
    await flush()
    expect(created).toEqual(expect.objectContaining({ ontologyId: 'ontology-1' }))
    app.unmount(); host.remove()
  })
})

it('removes text constraints when a property changes to a non-text value type', async () => {
  const property = reactive<Property>({ key: 'status', label: '状态', description: '', aliases: [], deprecated: false, ownerTypeKey: 'Pump', valueType: 'TEXT', multiplicity: 'SINGLE', fixedUnit: 'kg', constraints: { allowedValues: ['READY'] } })
  const { app, host } = mountApp({ setup: () => () => h(PropertyEditor, { modelValue: property, types: [{ key: 'Pump', label: '泵', description: '' }], 'onUpdate:modelValue': (value: Property) => Object.assign(property, value) }) })
  const allowedValues = host.querySelector('textarea[data-semantic-field="constraints.allowedValues"]') as HTMLTextAreaElement
  allowedValues.value = 'READY\n NOT_READY\n'
  allowedValues.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  expect(allowedValues.value).toBe('READY\n NOT_READY\n')
  allowedValues.dispatchEvent(new Event('blur', { bubbles: true }))
  await nextTick()
  expect(property.constraints?.allowedValues).toEqual(['READY', ' NOT_READY'])
  property.valueType = 'BOOLEAN'
  await nextTick()
  expect(property.constraints).toBeUndefined()
  expect(property.fixedUnit).toBeNull()
  app.unmount(); host.remove()
})


it('switches inspected properties without changing persisted fields or retaining another enum', async () => {
  const state = reactive<{ property: Property }>({ property: { key: 'size', label: '尺寸', description: '', ownerTypeKey: 'Pump', valueType: 'DECIMAL', multiplicity: 'SINGLE', fixedUnit: 'mm', constraints: { minimum: '0' } } })
  const { app, host } = mountApp({ setup: () => () => h(PropertyEditor, { modelValue: state.property, types: [], 'onUpdate:modelValue': (value: Property) => { state.property = value } }) })
  const text: Property = { key: 'status', label: '状态', description: '', ownerTypeKey: 'Pump', valueType: 'TEXT', multiplicity: 'SINGLE', fixedUnit: null, constraints: { allowedValues: ['READY'], minimum: null, maximum: null } }
  const before = JSON.stringify(text)
  state.property = text
  await nextTick()
  expect(JSON.stringify(state.property)).toBe(before)
  expect((host.querySelector('textarea[data-semantic-field="constraints.allowedValues"]') as HTMLTextAreaElement).value).toBe('READY')
  state.property = { ...text, key: 'notes', constraints: undefined }
  await nextTick()
  expect((host.querySelector('textarea[data-semantic-field="constraints.allowedValues"]') as HTMLTextAreaElement).value).toBe('')
  app.unmount(); host.remove()
})
