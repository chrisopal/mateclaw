import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { ontologyApi } from '../../api/ontologyApi'
import ModelingTaskPanel from '../components/ModelingTaskPanel.vue'
import { modelingTaskApi, type ModelingTask } from '../../api/modelingTaskApi'
vi.mock('../../api/ontologyApi', () => ({ ontologyApi: { sourceReviewSnapshots: vi.fn(), ensureBuilder: vi.fn() }, semanticRequest: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../../api/modelingTaskApi', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  modelingTaskApi: { list: vi.fn(), get: vi.fn(), decide: vi.fn() },
}))
const task: ModelingTask = {
  id: 'task',
  ontologyId: 'model',
  draftId: 'draft',
  goal: '设备管理',
  stage: 'AWAITING_CONFIRMATION',
  sources: [],
  proposals: [
    {
      id: 'p',
      status: 'PENDING',
      answers: {},
      input: {
        changes: [{ kind: 'CREATE_TERM', name: '设备' }],
        questions: ['设备归谁管理？'],
        evidence: [],
        samples: [],
      },
    },
  ],
}
let app: App, host: HTMLDivElement
const flush = async () => {
  await nextTick()
  await new Promise((r) => setTimeout(r, 0))
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(modelingTaskApi.list).mockResolvedValue([task])
  vi.mocked(modelingTaskApi.get).mockResolvedValue(task)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})
async function mount(canManage = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useWorkspaceStore()
  store.currentWorkspaceId = '1'
  host = document.createElement('div')
  document.body.append(host)
  const changed = vi.fn()
  app = createApp({
    render: () =>
      h(ModelingTaskPanel, {
        ontologyId: 'model',
        taskId: 'task',
        canManage,
        onChanged: changed,
      }),
  })
  app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: {} })).mount(host)
  await flush()
  return { store, changed }
}
it('recovers refreshed detail and requires question answers before human confirmation', async () => {
  const { changed } = await mount()
  expect(modelingTaskApi.get).toHaveBeenCalledWith(
    '1',
    'task',
    expect.any(AbortSignal),
  )
  expect(modelingTaskApi.decide).not.toHaveBeenCalled()
  const accept = [...host.querySelectorAll('button')].find((x) =>
    x.textContent?.includes('确认本批建议'),
  )!
  expect(accept.disabled).toBe(true)
  const input = host.querySelector('textarea')!
  input.value = '维修班组'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
  expect(accept.disabled).toBe(false)
  vi.mocked(modelingTaskApi.decide).mockResolvedValue({
    ...task,
    proposals: [{ ...task.proposals[0]!, status: 'ACCEPTED' }],
  })
  accept.click()
  await flush()
  expect(modelingTaskApi.decide).toHaveBeenCalledWith(
    '1',
    'task',
    'p',
    {
      operationId: expect.any(String),
      decision: 'ACCEPT',
      answers: { '设备归谁管理？': '维修班组' },
    },
    expect.any(AbortSignal),
  )
  expect(changed).toHaveBeenCalledOnce()
  expect(host.textContent).toContain('已采用')
})
it('uses refreshed stale status and never exposes accept for stale or viewer tasks', async () => {
  vi.mocked(modelingTaskApi.get).mockResolvedValue({
    ...task,
    proposals: [{ ...task.proposals[0]!, status: 'STALE' }],
  })
  await mount(false)
  expect(host.textContent).toContain('草稿或资料已变化')
  expect(host.textContent).not.toContain('确认本批建议')
  expect(modelingTaskApi.decide).not.toHaveBeenCalled()
})

it('shows the full goal once and suppresses empty nullable command details', async () => {
  const detail: ModelingTask = {
    ...task,
    goal: '建立设备、设备编号及故障关系的完整业务模型',
    proposals: [{ ...task.proposals[0]!, input: { ...task.proposals[0]!.input, changes: [{ kind: 'CREATE_TERM', termKind: 'OBJECT', name: '新设备', clientId: 'equipment', targetId: null, domainId: null, rangeId: null, field: null, value: null, language: null, operator: null, propertyId: null, fillerId: null, cardinality: null, originalAxiomId: null }] } }],
  }
  vi.mocked(modelingTaskApi.list).mockResolvedValue([detail])
  vi.mocked(modelingTaskApi.get).mockResolvedValue(detail)
  await mount()
  expect(host.textContent?.split(detail.goal)).toHaveLength(2)
  expect(host.textContent).toContain('任务 1 · 待确认')
  expect(host.textContent).not.toContain('null')
  expect(host.querySelector('.proposal li .semantic-muted')).toBeNull()
})

it('shows completed batch state for a READY task with accepted suggestions', async () => {
  const completed: ModelingTask = { ...task, stage: 'READY', proposals: [{ ...task.proposals[0]!, status: 'ACCEPTED' }] }
  vi.mocked(modelingTaskApi.list).mockResolvedValue([completed])
  vi.mocked(modelingTaskApi.get).mockResolvedValue(completed)
  await mount()
  expect(host.textContent).toContain('本批已确认，可继续建模')
  expect(host.textContent).not.toContain('待开始')
  expect(host.querySelector<HTMLDetailsElement>('.task-goal')?.open).toBe(false)
})

const incremental = { reviewId: 'review', bindingId: 'binding', baseRevisionId: 'base', oldSnapshotId: 'old', newSnapshotId: 'new', oldDigest: 'private-old-hash', newDigest: 'private-new-hash', affectedAxiomIds: ['a1', 'a2'] }
it('summarizes incremental scope and opens original and updated source text only on request', async () => {
  vi.mocked(modelingTaskApi.get).mockResolvedValue({ ...task, incremental })
  vi.mocked(ontologyApi.sourceReviewSnapshots).mockResolvedValue({ original: { sourceTitle: '原始规范', sourceText: '旧定义原文' }, observed: { sourceTitle: '修订规范', sourceText: '新定义原文' } } as never)
  await mount()
  expect(host.querySelector('[data-testid="incremental-model-scope"]')?.textContent).toContain('2 条受影响业务规则')
  expect(host.textContent).not.toContain('private-old-hash')
  expect(ontologyApi.sourceReviewSnapshots).not.toHaveBeenCalled()
  ;[...host.querySelectorAll('button')].find(b => b.textContent?.includes('对比原始依据与新资料'))!.click(); await flush()
  expect(ontologyApi.sourceReviewSnapshots).toHaveBeenCalledWith('1', 'model', 'review', expect.any(AbortSignal))
  expect(document.body.textContent).toContain('旧定义原文')
  expect(document.body.textContent).toContain('新定义原文')
})
it('discards late comparison text when workspace changes', async () => {
  vi.mocked(modelingTaskApi.get).mockResolvedValue({ ...task, incremental })
  let finish!: (value: never) => void
  vi.mocked(ontologyApi.sourceReviewSnapshots).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const { store } = await mount()
  ;[...host.querySelectorAll('button')].find(b => b.textContent?.includes('对比原始依据与新资料'))!.click()
  store.currentWorkspaceId = '2'; await flush()
  finish({ original: { sourceTitle: 'Old workspace', sourceText: 'PRIVATE-LATE-SOURCE' } } as never); await flush()
  expect(document.body.textContent).not.toContain('PRIVATE-LATE-SOURCE')
})
