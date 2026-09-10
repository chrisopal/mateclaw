import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import OntologySourcePanel from '../components/OntologySourcePanel.vue'
import { ontologyApi } from '../../api/ontologyApi'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string, fallback: string) => fallback ?? key, locale: { value: 'en-US' } }) }))
vi.mock('@/api/index', () => ({ workspaceTeamApi: { getAccess: vi.fn() } }))
vi.mock('../../api/ontologyApi', () => ({ ontologyApi: { axiomSources: vi.fn(), sourceReviews: vi.fn(), decideSourceReview: vi.fn() } }))
let app: App
const flushPromises = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
const props = { ontologyId: 'ontology', revisionId: 'revision', axioms: [], canManage: false, canReview: false }
beforeEach(() => { localStorage.clear(); localStorage.setItem('mc-workspace-id', 'old'); setActivePinia(createPinia()); vi.resetAllMocks() })
function panel(overrides: Partial<typeof props> = {}) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(OntologySourcePanel, { ...props, ...overrides }); app.use(createPinia()).use(ElementPlus)
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
  const input = wrapper.host.querySelector<HTMLInputElement>('input[placeholder="Decision reason"]')!
  input.value = 'Preserve original evidence'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  const button = [...wrapper.host.querySelectorAll('button')].find(b => b.textContent?.includes('Keep historical'))!
  button.click(); await flushPromises()
  expect(ontologyApi.decideSourceReview).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('KEEP_HISTORICAL')
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
  expect(host.textContent).toContain('source-a')
  expect(host.textContent).not.toContain('source-b')
  expect(host.querySelector('[data-testid="selected-axiom-source-count"]')?.textContent).toContain('1')
  expect(host.querySelector('[data-testid="selected-axiom-pending-count"]')?.textContent).toContain('1')
  ;[...host.querySelectorAll('button')].find(button => button.textContent?.includes('Clear axiom focus'))?.click()
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
