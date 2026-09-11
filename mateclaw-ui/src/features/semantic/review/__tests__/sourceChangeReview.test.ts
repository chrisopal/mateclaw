import { beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import SourceChangeReviewPanel from '../SourceChangeReviewPanel.vue'

vi.mock('../SourceFactRevision.vue', () => ({ default: { props: ['graphId', 'itemId', 'entities', 'document'], template: '<form data-testid="fact-revision-form" :data-graph="graphId" :data-item="itemId">事实修订表单</form>' } }))
const scope = vi.hoisted(() => ({ currentWorkspaceId: 'ws-1' }))
vi.mock('../../shared/useSemanticScope', () => ({
  useSemanticScope: () => ({
    workspace: scope,
    cancel: vi.fn(),
    begin: () => {
      const id = scope.currentWorkspaceId
      return { id, signal: new AbortController().signal, current: () => id === scope.currentWorkspaceId }
    },
  }),
}))
vi.mock('../../api/sourceChangeApi', () => ({
  sourceChangeApi: {
    pending: vi.fn().mockResolvedValue([{ id: 'item-1', changeId: 'change-1', itemKind: 'FACT', itemId: 'statement-1', itemRevision: 2, newDigest: 'sha256:new', oldDigest: 'sha256:old', reviewState: 'PENDING', sourceState: 'CHANGED' }]),
    items: vi.fn().mockResolvedValue([]),
    scan: vi.fn().mockResolvedValue({ runId: 'run-1', graphId: 'graph-1', graphMutationVersion: 8, changes: [] }),
    decide: vi.fn().mockResolvedValue({ id: 'item-1', reviewState: 'REVIEWED' }),
  },
}))
import { sourceChangeApi } from '../../api/sourceChangeApi'

beforeEach(() => { vi.clearAllMocks() })
async function mountPanel(overrides: Record<string, unknown> = {}) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(SourceChangeReviewPanel, { graphId: 'graph-1', graphVersion: 7, canScan: true, canReview: true, ...overrides })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
  return { app, host }
}

it('loads pending reviews and sends the current graph version when scanning', async () => {
  const { app, host } = await mountPanel()
  try {
    expect(sourceChangeApi.pending).toHaveBeenCalledWith('ws-1', 'graph-1', 100, expect.any(AbortSignal))
    const scanButton = [...host.querySelectorAll('button')].find(button => button.textContent?.includes('扫描来源变化')) as HTMLButtonElement
    scanButton.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(sourceChangeApi.scan).toHaveBeenCalledWith('ws-1', 'graph-1', expect.objectContaining({ expectedGraphVersion: 7, operationId: expect.any(String) }), expect.any(AbortSignal))
  } finally { app.unmount(); host.remove() }
})

it('states that human decisions do not automatically rewrite knowledge', async () => {
  const { app, host } = await mountPanel()
  try {
    expect(host.textContent).toContain('不会自动改写')
  } finally { app.unmount(); host.remove() }
})

const model = { source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: '', imports: [], policy: { version: '1', rules: [] } }, ontologyIri: 'urn:model', versionIri: null, documentDigest: '', importLockDigest: '', axioms: [] }
async function selectFact(host: HTMLElement) {
  const row = [...host.querySelectorAll<HTMLElement>('.el-table__row')].find(element => element.textContent?.includes('statement-1'))!
  expect(row).toBeTruthy(); row.click(); await nextTick()
}
it('opens the fact revision form for the real FACT CHANGED PENDING item contract', async () => {
  const { app, host } = await mountPanel({ document: model, entities: [] })
  try {
    await selectFact(host)
    const prepare = [...host.querySelectorAll('button')].find(button => button.textContent?.includes('准备事实修订'))!
    expect(prepare).toBeTruthy(); expect(prepare.disabled).toBe(false); prepare.click()
    await nextTick(); await new Promise(resolve => setTimeout(resolve, 0))
    const form = document.body.querySelector('[data-testid="fact-revision-form"]')
    expect(form).toBeTruthy()
    expect(form?.getAttribute('data-graph')).toBe('graph-1')
    expect(form?.getAttribute('data-item')).toBe('item-1')
  } finally { app.unmount(); host.remove() }
})
it('does not offer fact revision when source is unavailable', async () => {
  vi.mocked(sourceChangeApi.pending).mockResolvedValueOnce([{ id: 'item-1', itemKind: 'FACT', itemId: 'statement-1', sourceState: 'UNAVAILABLE', reviewState: 'PENDING' }] as never)
  const { app, host } = await mountPanel({ document: model, entities: [] })
  try {
    await selectFact(host)
    expect(host.textContent).not.toContain('准备事实修订')
    expect(host.textContent).toContain('资料不可访问，无法据此提出新结论。')
    expect(document.body.querySelector('[data-testid="fact-revision-form"]')).toBeNull()
  } finally { app.unmount(); host.remove() }
})
it('does not offer fact revision without review permission', async () => {
  const { app, host } = await mountPanel({ document: model, entities: [], canReview: false })
  try {
    await selectFact(host)
    expect(host.textContent).not.toContain('准备事实修订')
    expect(document.body.querySelector('[data-testid="fact-revision-form"]')).toBeNull()
  } finally { app.unmount(); host.remove() }
})
