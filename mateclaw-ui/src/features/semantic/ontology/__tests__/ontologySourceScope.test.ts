import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import OntologySourcePanel from '../components/OntologySourcePanel.vue'
import { sourceIncrementalApi } from '../../api/sourceIncrementalApi'
import { ontologyApi } from '../../api/ontologyApi'
import { exactQuoteRange, sourceSelectionApi } from '../../api/sourceSelectionApi'
import type { AxiomDescriptor } from '../../api/types'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string, fallback: string) => fallback ?? key, locale: { value: 'en-US' } }) }))
vi.mock('@/api/index', () => ({ workspaceTeamApi: { getAccess: vi.fn() } }))
vi.mock('../../api/sourceIncrementalApi', () => ({ sourceIncrementalApi: { modelTask: vi.fn() } }))
vi.mock('../../api/ontologyApi', () => ({ ontologyApi: { axiomSources: vi.fn(), sourceReviews: vi.fn(), decideSourceReview: vi.fn(), bindAxiomSource: vi.fn(), sourceReviewSnapshots: vi.fn(), scanSourceReviews: vi.fn() } }))
vi.mock('../../api/sourceSelectionApi', () => ({ sourceSelectionApi: { knowledgeBases: vi.fn().mockResolvedValue([]), materials: vi.fn().mockResolvedValue([]), material: vi.fn() }, exactQuoteRange: (text: string, quote: string) => { const value = quote.trim(); const start = text.indexOf(value); if (!value || start < 0) return null; const begin = [...text.slice(0, start)].length; return { startCodePoint: begin, endCodePoint: begin + [...value].length, exactQuote: value } } }))
let app: App
const flushPromises = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
const props: { ontologyId: string; revisionId: string; axioms: AxiomDescriptor[]; canManage: boolean; canReview: boolean; draftVersion?: number; focusedAxiomId?: string } = { ontologyId: 'ontology', revisionId: 'revision', axioms: [], canManage: false, canReview: false }
beforeEach(() => { localStorage.clear(); localStorage.setItem('mc-workspace-id', 'old'); setActivePinia(createPinia()); vi.resetAllMocks() })
function panel(overrides: Partial<typeof props> = {}, onModelingTask = vi.fn()) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(OntologySourcePanel, { ...props, ...overrides, onModelingTask }); app.use(createPinia()).use(ElementPlus)
  // Keep the same active store instance used by the component and test.
  useWorkspaceStore().currentWorkspaceId = 'old'; app.mount(host)
  return { host, text: () => host.textContent ?? '' }
}
it('clears displayed source quotes immediately when the workspace changes', async () => {
  vi.mocked(ontologyApi.axiomSources).mockResolvedValueOnce([{ exactQuote: 'PRIVATE-OLD-SOURCE' }] as never)
  const wrapper = panel(); await flushPromises()
  expect(wrapper.text()).toContain('PRIVATE-OLD-SOURCE')
  vi.mocked(ontologyApi.axiomSources).mockImplementationOnce(() => new Promise(() => {}))
  useWorkspaceStore().currentWorkspaceId = 'new'; await flushPromises()
  expect(wrapper.text()).not.toContain('PRIVATE-OLD-SOURCE')
})
it('ignores late source results from a previous workspace', async () => {
  let finish!: (value: never) => void
  vi.mocked(ontologyApi.axiomSources).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  vi.mocked(ontologyApi.axiomSources).mockResolvedValueOnce([])
  const wrapper = panel()
  useWorkspaceStore().currentWorkspaceId = 'new'; await flushPromises()
  finish([{ exactQuote: 'LATE-PRIVATE-SOURCE' }] as never); await flushPromises()
  expect(wrapper.text()).not.toContain('LATE-PRIVATE-SOURCE')
  expect(vi.mocked(ontologyApi.axiomSources).mock.calls.map(call => call[0])).toEqual(['old', 'new'])
})

it('refreshes source binding state after a review decision is persisted', async () => {
  const binding = { id: 'binding', axiomId: 'axiom', sourceRef: 'raw', exactQuote: 'Original source', currentSourceState: 'UNAVAILABLE', reviewState: 'PENDING' }
  const review = { id: 'review', bindingId: 'binding', sourceState: 'UNAVAILABLE', observedDigest: 'digest', reviewState: 'PENDING' }
  vi.mocked(ontologyApi.axiomSources).mockResolvedValueOnce([binding] as never)
    .mockResolvedValue([{ ...binding, reviewState: 'KEEP_HISTORICAL' }] as never)
  vi.mocked(ontologyApi.sourceReviews).mockResolvedValue([review] as never)
  vi.mocked(ontologyApi.decideSourceReview).mockResolvedValue({ ...review, reviewState: 'REVIEWED', decision: 'KEEP_HISTORICAL' } as never)
  const wrapper = panel({ canReview: true }); await flushPromises()
  wrapper.host.querySelector<HTMLButtonElement>('[data-testid="toggle-pending-reviews"]')!.click(); await flushPromises()
  const input = wrapper.host.querySelector<HTMLInputElement>('input[placeholder="Reason for this decision"]')!
  input.value = 'Preserve original evidence'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  const button = [...wrapper.host.querySelectorAll('button')].find(b => b.textContent?.includes('Keep history'))!
  button.click(); await flushPromises()
  expect(ontologyApi.decideSourceReview).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('Historical evidence kept')
  expect(wrapper.text()).not.toContain('PENDING')
})

it('filters bindings and pending reviews to the inspected axiom and allows clearing focus', async () => {
  const axioms = [
    { axiomId: 'a1', axiomType: 'Declaration', rendering: 'Declaration(Class(<urn:A>))', signatureIris: [], annotations: [], logical: true },
    { axiomId: 'a2', axiomType: 'Declaration', rendering: 'Declaration(Class(<urn:B>))', signatureIris: [], annotations: [], logical: true },
  ]
  vi.mocked(ontologyApi.axiomSources).mockResolvedValue([
    { id: 'b1', axiomId: 'a1', sourceRef: 'source-a', exactQuote: 'A source', revisionId: 'revision', sourceSnapshotId: 's1', knowledgeBaseId: 'kb', sourceDigest: 'd1', startCodePoint: 0, endCodePoint: 1, origin: 'EXPERT', currentSourceState: 'CURRENT', reviewState: 'PENDING' },
    { id: 'b2', axiomId: 'a2', sourceRef: 'source-b', exactQuote: 'B source', revisionId: 'revision', sourceSnapshotId: 's2', knowledgeBaseId: 'kb', sourceDigest: 'd2', startCodePoint: 0, endCodePoint: 1, origin: 'EXPERT', currentSourceState: 'CURRENT', reviewState: 'PENDING' },
  ] as never)
  vi.mocked(ontologyApi.sourceReviews).mockResolvedValue([
    { id: 'r1', bindingId: 'b1', observedDigest: 'd1', sourceState: 'CURRENT', reviewState: 'PENDING', decision: null, reason: null },
    { id: 'r2', bindingId: 'b2', observedDigest: 'd2', sourceState: 'CURRENT', reviewState: 'PENDING', decision: null, reason: null },
  ] as never)
  const clearFocus = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  const focusProps = reactive({ ...props, axioms, canReview: true, focusedAxiomId: 'a1' })
  app = createApp({ render: () => h(OntologySourcePanel, { ...focusProps, onClearFocus: clearFocus }) })
  app.use(createPinia()).use(ElementPlus); useWorkspaceStore().currentWorkspaceId = 'old'; app.mount(host)
  await flushPromises()
  expect(host.textContent).toContain('Linked material')
  expect(host.textContent).not.toContain('B source')
  expect(host.querySelector('[data-testid="selected-axiom-source-count"]')?.textContent).toContain('1')
  expect(host.querySelector('[data-testid="selected-axiom-pending-count"]')?.textContent).toContain('1')
  ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('Clear rule focus'))?.click()
  expect(clearFocus).toHaveBeenCalledTimes(1)
})

it('distinguishes source binding loading and failure from an empty result', async () => {
  let reject!: (error: Error) => void
  vi.mocked(ontologyApi.axiomSources).mockImplementationOnce(() => new Promise((_, fail) => { reject = fail }))
  const wrapper = panel()
  await nextTick()
  expect(wrapper.host.querySelector('[data-testid="source-bindings-status"]')?.textContent).toContain('Loading')
  expect(wrapper.host.querySelector('[data-testid="source-bindings-status"]')?.textContent).not.toContain('0')
  reject(new Error('source API offline')); await flushPromises()
  expect(wrapper.host.querySelector('[data-testid="source-bindings-status"]')?.textContent).toContain('Error')
  expect(wrapper.host.querySelector('[data-testid="source-bindings-status"]')?.textContent).not.toContain('0')
})

it('keeps pending review count unknown when the viewer cannot access reviews', async () => {
  vi.mocked(ontologyApi.axiomSources).mockResolvedValueOnce([])
  const wrapper = panel()
  await flushPromises()
  expect(wrapper.host.querySelector('[data-testid="selected-axiom-pending-count"]')?.textContent).toContain('Unknown')
  expect(wrapper.host.querySelector('[data-testid="selected-axiom-pending-count"]')?.textContent).not.toContain('0')
  expect(ontologyApi.sourceReviews).not.toHaveBeenCalled()
})

it('ignores a late document list after the user changes knowledge base', async () => {
  const axioms = [{ axiomId: 'a1', axiomType: 'SubClassOf', rendering: 'technical rule', signatureIris: [], annotations: [], logical: true }]
  vi.mocked(ontologyApi.axiomSources).mockResolvedValue([])
  vi.mocked(ontologyApi.sourceReviews).mockResolvedValue([])
  let resolveFirst!: (items: never[]) => void
  vi.mocked(sourceSelectionApi.knowledgeBases).mockResolvedValue([
    { id: 'kb-old', name: '旧知识库' }, { id: 'kb-new', name: '新知识库' },
  ])
  vi.mocked(sourceSelectionApi.materials).mockImplementation((_ws, kb) => kb === 'kb-old'
    ? new Promise(resolve => { resolveFirst = resolve })
    : Promise.resolve([{ id: 'new-doc', title: '新资料' }]))
  const wrapper = panel({ axioms, canManage: true, draftVersion: 2 })
  await flushPromises()
  wrapper.host.querySelector<HTMLButtonElement>('[data-testid="add-reference"]')!.click(); await flushPromises()
  const choose = async (testid: string, label: string) => {
    wrapper.host.querySelector<HTMLElement>(`[data-testid="${testid}"]`)!.click(); await nextTick()
    ;[...document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(item => item.textContent?.includes(label))!.click(); await flushPromises()
  }
  await choose('reference-kb-select', '旧知识库')
  await choose('reference-kb-select', '新知识库')
  resolveFirst([{ id: 'old-doc', title: '旧资料' }] as never); await flushPromises()
  expect(vi.mocked(sourceSelectionApi.materials).mock.calls.map(call => call[1])).toEqual(['kb-old', 'kb-new'])
  expect(document.body.textContent).toContain('新资料')
  expect(document.body.textContent).not.toContain('旧资料')
})

it('shows the selected source text beside the exact excerpt field', async () => {
  const axioms = [{ axiomId: 'a1', axiomType: 'SubClassOf', rendering: 'technical rule', signatureIris: [], annotations: [], logical: true }]
  vi.mocked(ontologyApi.axiomSources).mockResolvedValue([])
  vi.mocked(ontologyApi.sourceReviews).mockResolvedValue([])
  vi.mocked(sourceSelectionApi.knowledgeBases).mockResolvedValue([{ id: 'kb', name: '设备知识库' }])
  vi.mocked(sourceSelectionApi.materials).mockResolvedValue([{ id: 'doc', title: '测量规范' }])
  vi.mocked(sourceSelectionApi.material).mockResolvedValue({
    knowledgeBaseId: 'kb', sourceRef: 'doc', sourceTitle: '测量规范',
    sourceText: '第一行\n设备😀用于测量。', sourceDigest: 'digest',
  })
  const wrapper = panel({ axioms, canManage: true, draftVersion: 2 })
  await flushPromises()
  wrapper.host.querySelector<HTMLButtonElement>('[data-testid="add-reference"]')!.click(); await flushPromises()
  const choose = async (testid: string, label: string) => {
    wrapper.host.querySelector<HTMLElement>(`[data-testid="${testid}"]`)!.click(); await nextTick()
    ;[...document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(item => item.textContent?.includes(label))!.click(); await flushPromises()
  }
  await choose('reference-kb-select', '设备知识库')
  await choose('reference-document-select', '测量规范')
  expect(document.body.querySelector('[data-testid="reference-source-preview"]')?.textContent).toContain('设备😀用于测量。')
  expect(document.body.querySelector('[data-testid="reference-excerpt"]')).toBeTruthy()
})

it('rejects a missing excerpt and counts Unicode code points for a valid excerpt', () => {
  expect(exactQuoteRange('设备😀用于测量。', '不存在')).toBeNull()
  expect(exactQuoteRange('设备😀用于测量。', '😀')).toEqual({ startCodePoint: 2, endCodePoint: 3, exactQuote: '😀' })
})

async function pendingReview(sourceState = 'CHANGED', onModelingTask = vi.fn()) {
  vi.mocked(ontologyApi.axiomSources).mockResolvedValue([])
  vi.mocked(ontologyApi.sourceReviews).mockResolvedValue([{ id: 'review', bindingId: 'binding', sourceState, observedDigest: 'observed-v2', reviewState: 'PENDING' }] as never)
  const wrapper = panel({ canReview: true }, onModelingTask)
  await flushPromises()
  wrapper.host.querySelector<HTMLButtonElement>('[data-testid="toggle-pending-reviews"]')!.click()
  await flushPromises()
  return wrapper
}
it('starts a real incremental task once and emits its ID instead of marking a review remodeled', async () => {
  const selected = vi.fn()
  let finish!: (value: never) => void
  vi.mocked(sourceIncrementalApi.modelTask).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const wrapper = await pendingReview('CHANGED', selected)
  const input = wrapper.host.querySelector<HTMLInputElement>('input[placeholder="Reason for this decision"]')!
  input.value = 'Check revised equipment definition'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  const button = wrapper.host.querySelector<HTMLButtonElement>('[data-testid="remodel-source"]')!
  button.click(); button.click(); await nextTick()
  expect(sourceIncrementalApi.modelTask).toHaveBeenCalledTimes(1)
  expect(vi.mocked(sourceIncrementalApi.modelTask).mock.calls[0]!.slice(0, 4)).toEqual(['old', 'ontology', 'review', { expectedObservedDigest: 'observed-v2', goal: 'Check revised equipment definition' }])
  expect(ontologyApi.decideSourceReview).not.toHaveBeenCalled()
  finish({ id: 'incremental-task' } as never); await flushPromises()
  expect(selected).toHaveBeenCalledWith('incremental-task')
})
it('does not offer automatic remodeling for unavailable material', async () => {
  const wrapper = await pendingReview('UNAVAILABLE')
  const button = wrapper.host.querySelector<HTMLButtonElement>('[data-testid="remodel-source"]')!
  expect(button.disabled).toBe(true); button.click(); await flushPromises()
  expect(sourceIncrementalApi.modelTask).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('Unavailable material cannot start remodeling.')
  expect(wrapper.text()).toContain('Keep history')
  expect(wrapper.text()).not.toContain('Remodeled')
})
it('ignores a late incremental task response after workspace switch', async () => {
  const selected = vi.fn()
  let finish!: (value: never) => void
  vi.mocked(sourceIncrementalApi.modelTask).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const wrapper = await pendingReview('CHANGED', selected)
  wrapper.host.querySelector<HTMLButtonElement>('[data-testid="remodel-source"]')!.click()
  useWorkspaceStore().currentWorkspaceId = 'new'; await flushPromises()
  finish({ id: 'old-workspace-task' } as never); await flushPromises()
  expect(selected).not.toHaveBeenCalled()
})
