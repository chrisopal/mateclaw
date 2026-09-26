import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import BiddingAnalysis from '../components/BiddingAnalysis.vue'
import BiddingEvidenceDrawer from '../components/BiddingEvidenceDrawer.vue'
import { biddingApi } from '../api/biddingApi'

vi.mock('../api/biddingApi', () => ({ biddingApi: { evidence: vi.fn() } }))

let app: App | undefined, host: HTMLElement | undefined
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
afterEach(() => { app?.unmount(); host?.remove(); app = undefined; host = undefined; vi.clearAllMocks() })
function mount(component: Parameters<typeof createApp>[0], props: Record<string, unknown>) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(component, props); app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } })).mount(host)
  return host
}

it('renders schema-shaped results in all four tabs and emits the selected evidence reference', async () => {
  const evidenceRef = { sourceId: 'src-1', version: 1, blockId: 'block-1', quote: 'Quoted tender text' }
  const skills = {
    'bidding-tender-profile': { basicInfo: { project: 'Bridge tender', tenderer: 'City', lot: 'Lot 1' }, deadlines: [{ name: 'Submission', value: '2026-10-01', evidenceRefs: [evidenceRef] }], coverage: { processedBlockIds: [], unprocessedBlockIds: [] }, warnings: [] },
    'bidding-elimination-analysis': { items: [{ id: 'e1', text: 'Signature required', scope: 'Whole bid', trigger: null, evidenceRefs: [evidenceRef], unknowns: [{ field: 'Signer', reason: 'Authority not stated' }] }], coverage: { processedBlockIds: [], unprocessedBlockIds: [] }, warnings: [] },
    'bidding-requirement-analysis': { requirements: [{ id: 'r1', text: 'Provide warranty', category: 'COMMERCIAL', constraints: ['At least 3 years'], acceptance: 'Warranty certificate', evidenceRefs: [evidenceRef], unknowns: [{ field: 'Start date', reason: 'Not specified' }] }], coverage: { processedBlockIds: [], unprocessedBlockIds: [] }, warnings: [] },
    'bidding-scoring-analysis': { criteria: [{ id: 'c1', parentId: null, title: 'Technical plan', score: '20', unit: 'points', rule: 'Best plan scores highest', requiredProof: 'Implementation plan', evidenceRefs: [evidenceRef] }], totalChecks: [], coverage: { processedBlockIds: [], unprocessedBlockIds: [] }, warnings: [] },
  }
  const onEvidence = vi.fn()
  const root = mount(BiddingAnalysis, { analysis: { groups: [{ taskGroupId: 'g1', status: 'SUCCEEDED', skills, conflicts: [], complete: true }] }, canWrite: true, canApprove: false, onEvidence })
  await flush()
  expect(root.textContent).toContain('Bridge tender')
  for (const [label, visibleText] of [['Disqualification', 'Authority not stated'], ['Requirements', 'Warranty certificate'], ['Scoring', 'Best plan scores highest']]) {
    ;([...root.querySelectorAll('.el-tabs__item')].find(tab => tab.textContent?.includes(label)) as HTMLElement).click()
    await flush()
    expect(root.textContent).toContain(visibleText)
    expect(root.textContent).not.toContain('[object Object]')
  }
  ;([...root.querySelectorAll('.result-table button')].find(button => button.textContent?.includes('Evidence')) as HTMLButtonElement).click()
  expect(onEvidence).toHaveBeenCalledWith(evidenceRef)
})

it('renders evidence ReadBlock content with the referenced source version and no undefined source label', async () => {
  vi.mocked(biddingApi.evidence).mockResolvedValue({ id: 'block-1', locator: 'Page 2, paragraph 4', text: 'The bid must include a warranty.', pdfPage: 2 } as never)
  const visible = ref(false)
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ setup: () => () => h(BiddingEvidenceDrawer, { modelValue: visible.value, workspaceId: 'ws-1', projectId: 'p1', sourceRef: { sourceId: 'src-1', version: 1, blockId: 'block-1' } }) })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } })).mount(host)
  await flush()
  visible.value = true
  await flush()
  expect(biddingApi.evidence).toHaveBeenCalledWith('ws-1', 'p1', { sourceId: 'src-1', version: 1, blockId: 'block-1' }, expect.any(AbortSignal))
  expect(document.body.textContent).toContain('Page 2, paragraph 4')
  expect(document.body.textContent).toContain('The bid must include a warranty.')
  expect(document.body.textContent).toContain('V1')
  expect(document.body.textContent).not.toContain('undefined')
  expect(document.body.textContent).not.toContain('src-1')
})
