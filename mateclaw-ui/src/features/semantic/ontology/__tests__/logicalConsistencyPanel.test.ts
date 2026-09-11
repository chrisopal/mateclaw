import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import zh from '@/i18n/locales/zh-CN'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import LogicalConsistencyPanel from '../components/LogicalConsistencyPanel.vue'
import { ontologyApi } from '../../api/ontologyApi'

vi.mock('../../api/ontologyApi', () => ({ ontologyApi: { reason: vi.fn() } }))

let app: App | undefined
let host: HTMLDivElement | undefined
const reason = vi.mocked(ontologyApi.reason)
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const projection = {
  schemaVersion: 'ontology-display-v1', documentDigest: 'digest', importLockDigest: 'imports',
  nodes: [{ id: 'class:urn:Pump', iri: 'urn:Pump', kind: 'class' as const, labels: [{ value: '泵', language: 'zh-CN', axiomId: 'label' }], axiomIds: ['class'], features: [], imported: false }],
  edges: [], axiomRefs: [], coverage: { total: 1, returned: 1, truncated: false, dependencyScope: 'ROOT', lockedImportCount: 0 },
}
const report = (consistent: boolean | null, status = 'CONSISTENT') => ({ draftVersion: 3, inputDigest: 'sha256:input', status, consistent, unsatisfiableClasses: consistent === false ? ['urn:Pump'] : [], message: '' })

function mount(overrides: Record<string, unknown> = {}) {
  const pinia = createPinia(); setActivePinia(pinia); useWorkspaceStore().currentWorkspaceId = 'ws'
  host = document.createElement('div'); document.body.append(host)
  const props = reactive('ontologyId' in overrides ? overrides : { ontologyId: 'ontology', draftVersion: 3, dirty: false, projection, editable: true, ...overrides })
  const Root = defineComponent({ setup: () => () => h(LogicalConsistencyPanel, { ...props } as any) })
  app = createApp(Root)
  app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  return host
}
function runButton(root: HTMLElement) { return [...root.querySelectorAll('button')].find(button => button.textContent?.includes('运行逻辑检查')) as HTMLButtonElement }
function cleanup() { const current = app; app = undefined; current?.unmount(); host?.remove(); host = undefined }

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('logical consistency check', () => {
  it('renders true, false, and null consistency using business labels', async () => {
    reason.mockResolvedValueOnce(report(true)); let root = mount(); await flush(); runButton(root).click(); await flush()
    expect(root.textContent).toContain('未发现逻辑矛盾')
    cleanup()

    reason.mockResolvedValueOnce(report(false)); root = mount(); await flush(); runButton(root).click(); await flush()
    expect(root.textContent).toContain('模型存在矛盾'); expect(root.textContent).toContain('泵'); expect(root.textContent).not.toContain('urn:Pump')
    cleanup()

    reason.mockResolvedValueOnce(report(null)); root = mount(); await flush(); runButton(root).click(); await flush()
    expect(root.textContent).toContain('未能完成检查')
  })

  it('stops waiting and suppresses a late server response', async () => {
    let resolve!: (value: ReturnType<typeof report>) => void
    reason.mockImplementationOnce((_ws, _id, _version, signal) => {
      expect(signal).toBeInstanceOf(AbortSignal)
      return new Promise(done => { resolve = done })
    })
    const root = mount(); await flush(); runButton(root).click(); await flush()
    const stop = [...root.querySelectorAll('button')].find(button => button.textContent?.includes('停止等待')) as HTMLButtonElement
    stop.click(); await nextTick(); expect(root.textContent).toContain('已停止等待'); resolve(report(true)); await flush()
    expect(root.querySelector('.logical-check-result')).toBeNull()
  })

  it('clears a running result when draft version changes and aborts the request', async () => {
    let resolve!: (value: ReturnType<typeof report>) => void
    let signal!: AbortSignal
    reason.mockImplementationOnce((_ws, _id, _version, requestSignal) => { signal = requestSignal!; return new Promise(done => { resolve = done }) })
    const props = reactive({ ontologyId: 'ontology', draftVersion: 3, dirty: false, projection, editable: true })
    const root = mount(props); await flush(); runButton(root).click(); await flush(); props.draftVersion = 4; await flush()
    expect(signal.aborted).toBe(true); resolve(report(true)); await flush(); expect(root.textContent).toContain('尚未执行逻辑检查')
    expect(root.querySelector('.logical-check-result')).toBeNull()
  })

  it('surfaces timeout, unsupported, and stale errors explicitly', async () => {
    const cases = [
      [{ code: 'ECONNABORTED', message: 'timeout' }, '超时'],
      [{ code: 'UNSUPPORTED', message: 'reasoner unavailable' }, '不支持'],
      [{ code: 'REASONING_STALE', message: 'stale draft' }, '旧版本'],
    ] as const
    for (const [failure, expected] of cases) {
      reason.mockRejectedValueOnce(failure); const root = mount(); await flush(); runButton(root).click(); await flush(); expect(root.textContent).toContain(expected)
      cleanup()
    }
  })

  it('treats unknown worker statuses as errors and flags satisfiable results with unsatisfiable types', async () => {
    reason.mockResolvedValueOnce({ ...report(true), unsatisfiableClasses: ['urn:Pump'] }); let root = mount(); await flush(); runButton(root).click(); await flush()
    expect(root.textContent).toContain('存在无法成立的对象类型'); expect(root.textContent).toContain('泵')
    cleanup()
    reason.mockResolvedValueOnce({ ...report(null), status: 'PARSE_ERROR', message: 'parser detail' }); root = mount(); await flush(); runButton(root).click(); await flush()
    expect(root.textContent).toContain('逻辑检查失败'); expect(root.textContent).toContain('parser detail')
  })
})
