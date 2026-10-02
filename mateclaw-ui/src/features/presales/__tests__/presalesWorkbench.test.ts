import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import enMessages from '@/i18n/locales/en-US'
import zhMessages from '@/i18n/locales/zh-CN'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import Workbench from '../pages/PresalesWorkbench.vue'
import { presalesApi } from '../api/presalesApi'
vi.mock('../api/presalesApi', () => ({
  presalesApi: Object.fromEntries(
    [
      'members',
      'capabilities',
      'list',
      'get',
      'create',
      'update',
      'command',
      'employees',
      'generate',
      'evidence',
      'file',
      'handoff',
      'sources',
      'statements',
    ].map((key) => [key, vi.fn()]),
  ),
}))
vi.mock('../shared/locale', () => ({ label: (_zh: string, en: string) => en }))
vi.mock('@/stores/useWorkspaceStore', () => ({
  useWorkspaceStore: () => ({
    currentWorkspaceId: '90071992547409999',
    registerBeforeSwitch: () => () => {},
  }),
}))
const project = {
  id: '90071992547409998',
  workspaceId: '90071992547409999',
  version: 3,
  name: 'Plant A',
  customer: 'Customer',
  ownerId: '90071992547409997',
  status: 'ACTIVE',
  materials: [],
  requirements: [],
  clarifications: [],
  baselines: [],
  fitGaps: [],
  solutions: [],
  reviews: [],
  releases: [],
  tasks: [],
}
let app: ReturnType<typeof createApp> | undefined
let changeLocale: (locale: 'en-US' | 'zh-CN') => void
async function settle() {
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
  }
}
async function mount(path = '/presales') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/presales/:projectId?', component: Workbench }],
  })
  await router.push(path)
  await router.isReady()
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp(Workbench)
  app.use(router)
  app.use(ElementPlus)
  const translations = createI18n({
    legacy: false,
    locale: 'en-US',
    messages: { 'en-US': enMessages, 'zh-CN': zhMessages },
  })
  changeLocale = (locale) => {
    translations.global.locale.value = locale
  }
  app.use(translations)
  app.mount(root)
  await settle()
}
function button(label: string): HTMLButtonElement {
  const result = [...document.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(presalesApi.members).mockResolvedValue([
    { id: 'membership', userId: project.ownerId, nickname: 'Jane Doe', username: 'jane' },
  ])
  vi.mocked(presalesApi.capabilities).mockResolvedValue({
    enabled: true,
    semanticEnabled: true,
    canWrite: true,
    canApprove: true,
  })
  vi.mocked(presalesApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  vi.mocked(presalesApi.get).mockResolvedValue(structuredClone(project))
  vi.mocked(presalesApi.employees).mockResolvedValue([])
  vi.mocked(presalesApi.sources).mockResolvedValue([])
  vi.mocked(presalesApi.statements).mockResolvedValue([])
})
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
})
describe('presales workspace behavior', () => {
  it('explains restricted sources, prevents generation and preserves material repair', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      agentId: 'employee-1',
      sourceAccessRestricted: true,
    })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Source access is restricted')
    expect(button('Delegate to employee').disabled).toBe(true)
    expect(button('Ask employee to analyze').disabled).toBe(true)
    expect(button('Edit project').disabled).toBe(false)
    expect(button('Assign employee').disabled).toBe(false)
    const materials = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find((item) =>
      item.textContent?.includes('Materials'),
    )!
    materials.click()
    await settle()
    expect(button('Bind material').disabled).toBe(false)
    expect(presalesApi.generate).not.toHaveBeenCalled()
    button('Bind material').click()
    await settle()
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, agentId: 'employee-1' })
    // Existing form requires a KB ID; use its advanced direct-reference field.
    document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
    const kbInput = document.querySelector<HTMLInputElement>('.el-dialog details input')!
    kbInput.value = 'kb-1'
    kbInput.dispatchEvent(new Event('input', { bubbles: true }))
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({ action: 'BIND_MATERIAL' }),
    )
    expect(document.body.textContent).not.toContain('Source access is restricted')
    expect(button('Delegate to employee').disabled).toBe(false)
  })

  it('clears an open source drawer and ignores its late response after a restricted command', async () => {
    let resolveEvidence!: (value: { id: string; text: string }) => void
    vi.mocked(presalesApi.evidence).mockReturnValue(
      new Promise((resolve) => {
        resolveEvidence = resolve
      }),
    )
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      requirements: [
        { id: 'req-1', title: 'Private source', graphId: 'graph-1', evidenceIds: ['evidence-1'] },
      ],
    })
    await mount(`/presales/${project.id}`)
    const tab = (text: string) =>
      [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find((item) =>
        item.textContent?.includes(text),
      )!
    tab('Requirements & questions').click()
    await settle()
    button('Evidence').click()
    await settle()
    expect(presalesApi.evidence).toHaveBeenCalled()
    expect(document.body.textContent).toContain('Private source')
    tab('Materials').click()
    await settle()
    button('Bind material').click()
    await settle()
    document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
    const kbInput = document.querySelector<HTMLInputElement>('.el-dialog details input')!
    kbInput.value = 'kb-1'
    kbInput.dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, sourceAccessRestricted: true })
    button('Save').click()
    await settle()
    resolveEvidence({ id: 'evidence-1', text: 'Late private content' })
    await settle()
    expect(document.body.textContent).toContain('Source access is restricted')
    expect(document.body.textContent).not.toContain('Private source')
    expect(document.body.textContent).not.toContain('Late private content')
  })

  it('clears a source-derived editor when a running task returns a restricted project', async () => {
    const active = {
      ...project,
      requirements: [{ id: 'req-1', title: 'Private draft', description: 'Private source text' }],
      tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
    }
    vi.mocked(presalesApi.get)
      .mockResolvedValueOnce(active)
      .mockResolvedValue({ ...project, sourceAccessRestricted: true })
    await mount(`/presales/${project.id}`)
    const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find(
      (item) => item.textContent === 'Requirements & questions',
    )!
    tab.click()
    await settle()
    button('Revise').click()
    await settle()
    expect(document.querySelector<HTMLInputElement>('.el-dialog input')?.value).toBe(
      'Private draft',
    )
    await new Promise((resolve) => setTimeout(resolve, 1100))
    await settle()
    expect(document.body.textContent).toContain('Source access is restricted')
    expect(document.querySelector<HTMLInputElement>('.el-dialog input')?.value).not.toBe(
      'Private draft',
    )
    expect(document.body.textContent).not.toContain('Private source text')
  })

  it('removes previously loaded source content when task polling receives HTTP 403', async () => {
    vi.mocked(presalesApi.get)
      .mockResolvedValueOnce({
        ...project,
        context: { text: 'Previously authorized source' },
        tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
      })
      .mockRejectedValue({ response: { status: 403, data: { msg: 'Source access denied' } } })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Previously authorized source')
    await new Promise((resolve) => setTimeout(resolve, 1100))
    await settle()
    expect(document.body.textContent).toContain('Source access denied')
    expect(button('Edit project').disabled).toBe(true)
    expect(document.body.textContent).not.toContain('Previously authorized source')
  })

  it.each(['file', 'handoff'] as const)(
    'rejects a pending %s download after restriction',
    async (kind) => {
      let finish!: () => void
      if (kind === 'file')
        vi.mocked(presalesApi.file).mockReturnValue(
          new Promise((resolve) => {
            finish = () => resolve(new Blob(['Private file']))
          }),
        )
      else
        vi.mocked(presalesApi.handoff).mockReturnValue(
          new Promise((resolve) => {
            finish = () => resolve({ id: 'handoff', text: 'Private handoff' })
          }),
        )
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
      try {
        const active = {
          ...project,
          releases: [
            { id: 'release-1', status: 'PUBLISHED', files: [{ filename: 'solution.pdf' }] },
          ],
          tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
        }
        vi.mocked(presalesApi.get)
          .mockResolvedValueOnce(active)
          .mockResolvedValue({ ...project, sourceAccessRestricted: true })
        await mount(`/presales/${project.id}`)
        const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find(
          (item) => item.textContent === 'Review & outputs',
        )!
        tab.click()
        await settle()
        button(kind === 'file' ? 'Download solution.pdf' : 'Export internal handoff').click()
        await settle()
        expect(kind === 'file' ? presalesApi.file : presalesApi.handoff).toHaveBeenCalled()
        await new Promise((resolve) => setTimeout(resolve, 1100))
        await settle()
        finish()
        await settle()
        expect(document.body.textContent).toContain('Source access is restricted')
        expect(click).not.toHaveBeenCalled()
      } finally {
        click.mockRestore()
      }
    },
  )

  it('inherits host locale changes for the restricted-source warning', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, sourceAccessRestricted: true })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Source access is restricted')
    changeLocale('zh-CN')
    await settle()
    expect(document.body.textContent).toContain('来源访问受限')
    expect(document.body.textContent).not.toContain('Source access is restricted')
  })

  it('shows real member names and preserves string user IDs when saving the owner', async () => {
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Jane Doe')
    expect(document.body.textContent).not.toContain(project.ownerId)
    button('Edit project').click()
    await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('Jane Doe')
    expect(document.querySelector('.el-dialog')?.textContent).not.toContain('Owner ID')
    vi.mocked(presalesApi.update).mockResolvedValue(project)
    button('Save').click()
    await settle()
    expect(presalesApi.update).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({ ownerId: project.ownerId }),
    )
  })

  it('renders empty server data without fixtures and creates using string IDs', async () => {
    await mount()
    expect(document.body.textContent).toContain('No projects.')
    expect(document.body.textContent).not.toContain('Plant A')
    button('New project').click()
    await settle()
    const inputs = document.querySelectorAll<HTMLInputElement>('.el-dialog input')
    inputs[0].value = 'New plant'
    inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1].value = 'New customer'
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.create).mockResolvedValue(project)
    button('Save').click()
    await settle()
    expect(presalesApi.create).toHaveBeenCalledWith(
      '90071992547409999',
      expect.objectContaining({
        name: 'New plant',
        customer: 'New customer',
        expectedVersion: 0,
        operationId: expect.any(String),
      }),
    )
  })
  it('retains edited input on version conflict and prevents blind resubmission', async () => {
    await mount(`/presales/${project.id}`)
    button('Edit project').click()
    await settle()
    const input = document.querySelector<HTMLInputElement>('.el-dialog input')!
    input.value = 'Unsaved change'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.update).mockRejectedValue({
      response: { status: 409, data: { msg: 'Project changed' } },
    })
    button('Save').click()
    await settle()
    expect(input.value).toBe('Unsaved change')
    expect(document.body.textContent).toContain('Project changed')
    expect(button('Save').disabled).toBe(true)
  })
  it('shows read-only state and disables writing from server capabilities', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({
      enabled: true,
      semanticEnabled: true,
      canWrite: false,
      canApprove: false,
    })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Read-only access')
    expect(button('Edit project').disabled).toBe(true)
    expect(button('Ask employee to analyze').disabled).toBe(true)
  })
  it('blocks execution without an assigned employee and has no model picker', async () => {
    await mount(`/presales/${project.id}`)
    button('Ask employee to analyze').click()
    await settle()
    expect(document.body.textContent).toContain('No employee assigned')
    expect(button('Start work').disabled).toBe(true)
    expect(document.querySelector('.el-dialog input[placeholder="Model"]')).toBeNull()
    expect(presalesApi.generate).not.toHaveBeenCalled()
  })
  it('treats a disabled feature 404 as unavailable, not an empty successful list', async () => {
    vi.mocked(presalesApi.capabilities).mockRejectedValue({ response: { status: 404 } })
    await mount()
    expect(document.body.textContent).toContain('Presales is disabled')
    expect(presalesApi.list).not.toHaveBeenCalled()
  })
})

describe('untrusted content and scope invariants', () => {
  it('renders AI source content as text and never creates external images', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      tasks: [
        {
          id: 'task-1',
          skill: 'S1',
          status: 'SUCCEEDED',
          result: {
            items: [
              {
                title: 'External source',
                text: '<img src="https://attacker.invalid/secret">',
                originKind: 'AI_SUGGESTION',
              },
            ],
            unknowns: [],
            assumptions: [],
          },
        },
      ],
    })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('<img src="https://attacker.invalid/secret">')
    expect(document.querySelector('img[src^="https://attacker.invalid"]')).toBeNull()
    button('Review proposal').click()
    await settle()
    expect(document.querySelector<HTMLTextAreaElement>('.el-dialog textarea')?.value).toContain(
      '<img',
    )
    expect(presalesApi.command).not.toHaveBeenCalled()
  })
})

describe('clarification workflow', () => {
  it('blocks answered records without source and submits the recorded source with version protection', async () => {
    const clarification = {
      id: 'question-1',
      question: 'Confirm line scope?',
      status: 'ANSWERED',
      answer: 'Two lines',
      answerSourceId: '',
    }
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, clarifications: [clarification] })
    await mount(`/presales/${project.id}`)
    const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find(
      (item) => item.textContent === 'Requirements & questions',
    )!
    tab.click()
    await settle()
    button('Revise answer').click()
    await settle()
    button('Save').click()
    await settle()
    expect(document.body.textContent).toContain('Provide an answer and its source')
    expect(presalesApi.command).not.toHaveBeenCalled()
    const source = document.querySelector<HTMLInputElement>(
      'input[placeholder="Meeting date, record title and section, or email subject"]',
    )!
    source.value = 'Meeting 2026-09-18, paragraph 2'
    source.dispatchEvent(new Event('input', { bubbles: true }))
    vi.mocked(presalesApi.command).mockResolvedValue({
      ...project,
      version: 4,
      clarifications: [{ ...clarification, answerSourceId: source.value }],
    })
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({
        expectedVersion: 3,
        action: 'SAVE_CLARIFICATION',
        payload: expect.objectContaining({ answerSourceId: 'Meeting 2026-09-18, paragraph 2' }),
      }),
    )
    expect(document.body.textContent).toContain('Meeting 2026-09-18, paragraph 2')
  })
})

describe('employee-driven workbench', () => {
  it('executes through the bound employee without a per-task model override', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      agentId: '90071992547409996',
      agentName: 'Presales specialist',
    })
    vi.mocked(presalesApi.employees).mockResolvedValue([
      { id: '90071992547409996', name: 'Presales specialist', enabled: true },
    ])
    vi.mocked(presalesApi.generate).mockResolvedValue({
      ...project,
      agentId: '90071992547409996',
      tasks: [],
    })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).not.toMatch(/G1|G2/)
    expect(
      [...document.querySelectorAll('button')].some((b) => b.textContent === 'Add question'),
    ).toBe(false)
    button('Delegate to employee').click()
    await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('Presales specialist')
    button('Start work').click()
    await settle()
    expect(presalesApi.generate).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({ skill: 'S1', expectedVersion: 3, taskGoal: expect.any(String) }),
    )
    expect(vi.mocked(presalesApi.generate).mock.calls[0][2]).not.toHaveProperty('modelId')
  })
  it('offers continuation to the employee after supplied information is saved', async () => {
    const question = {
      id: 'q',
      question: 'Scope?',
      impact: 'Scope',
      status: 'ANSWERED',
      answer: 'Two lines',
      answerSourceId: 'Meeting',
      proposedByTaskId: 'task',
    }
    const current = { ...project, agentId: 'employee', clarifications: [question] }
    vi.mocked(presalesApi.get).mockResolvedValue(current)
    vi.mocked(presalesApi.command).mockResolvedValue({ ...current, version: 4 })
    await mount(`/presales/${project.id}`)
    const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find(
      (item) => item.textContent === 'Requirements & questions',
    )!
    tab.click()
    await settle()
    button('Revise answer').click()
    await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain(
      'The employee needs this information',
    )
    button('Save').click()
    await settle()
    expect(document.body.textContent).toContain('Delegate to presales employee')
    expect(presalesApi.generate).not.toHaveBeenCalled()
  })
})
