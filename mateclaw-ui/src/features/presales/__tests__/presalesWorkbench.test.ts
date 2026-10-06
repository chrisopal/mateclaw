import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { currentLocale } from '@/i18n'
import enMessages from '@/i18n/locales/en-US'
import zhMessages from '@/i18n/locales/zh-CN'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElMessageBox } from 'element-plus'
import Workbench from '../pages/PresalesWorkbench.vue'
import {
  presalesApi,
  type PresalesHandoff,
  type PresalesProject,
  type PresalesRepairContext,
} from '../api/presalesApi'
vi.mock('../api/presalesApi', () => ({
  presalesApi: Object.fromEntries(
    [
      'members',
      'capabilities',
      'list',
      'get',
      'repairContext',
      'create',
      'update',
      'command',
      'employees',
      'generate',
      'cancelTask',
      'evidence',
      'file',
      'handoff',
      'sources',
      'statements',
    ].map((key) => [key, vi.fn()]),
  ),
}))
const initialHostLocale = currentLocale.value
const workspaceFixture = vi.hoisted(() => ({
  beforeSwitch: undefined as (() => Promise<boolean>) | undefined,
  current: {
    currentWorkspaceId: '90071992547409999',
    registerBeforeSwitch: (guard: () => Promise<boolean>) => {
      workspaceFixture.beforeSwitch = guard
      return () => {
        if (workspaceFixture.beforeSwitch === guard) workspaceFixture.beforeSwitch = undefined
      }
    },
  },
}))
vi.mock('@/stores/useWorkspaceStore', async () => {
  const { reactive } = await import('vue')
  workspaceFixture.current = reactive(workspaceFixture.current)
  return { useWorkspaceStore: () => workspaceFixture.current }
})
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
const repairProject: PresalesRepairContext = {
  ...project,
  sourceAccessRestricted: true,
  repairBindings: [],
  cases: [],
  contextCards: [],
  reviewDrafts: [],
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
    routes: [
      { path: '/presales/:projectId?', component: Workbench },
      { path: '/outside', component: { template: '<div>Outside page</div>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp(RouterView)
  app.use(router)
  app.use(ElementPlus)
  const translations = createI18n({
    legacy: false,
    locale: 'en-US',
    messages: { 'en-US': enMessages, 'zh-CN': zhMessages },
  })
  changeLocale = (locale) => {
    currentLocale.value = locale
    translations.global.locale.value = locale
  }
  app.use(translations)
  app.mount(root)
  await settle()
  return router
}
function button(label: string): HTMLButtonElement {
  const result = [...document.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}
function confirmed(value = ''): Awaited<ReturnType<typeof ElMessageBox.confirm>> {
  // Element Plus declares input data intersected with Action; runtime prompts return this object.
  return { action: 'confirm', value } as Awaited<ReturnType<typeof ElMessageBox.confirm>>
}
beforeEach(() => {
  vi.resetAllMocks()
  currentLocale.value = 'en-US'
  workspaceFixture.beforeSwitch = undefined
  workspaceFixture.current.currentWorkspaceId = project.workspaceId
  vi.mocked(presalesApi.members).mockResolvedValue([
    {
      id: 'membership',
      workspaceId: project.workspaceId,
      role: 'member',
      userId: project.ownerId,
      nickname: 'Jane Doe',
      username: 'jane',
    },
  ])
  vi.mocked(presalesApi.capabilities).mockResolvedValue({
    enabled: true,
    semanticEnabled: true,
    canWrite: true,
    canApprove: true,
  })
  vi.mocked(presalesApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  vi.mocked(presalesApi.get).mockResolvedValue(structuredClone(project))
  vi.mocked(presalesApi.repairContext).mockRejectedValue({ response: { status: 403 } })
  vi.mocked(presalesApi.employees).mockResolvedValue([])
  vi.mocked(presalesApi.sources).mockResolvedValue([])
  vi.mocked(presalesApi.statements).mockResolvedValue([])
})
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  currentLocale.value = initialHostLocale
})
describe('presales workspace behavior', () => {
  it('keeps ledger filter models, pagination and dashboard stage selection in the query session', async () => {
    vi.mocked(presalesApi.list).mockImplementation(async (_ws, params) =>
      params.pageSize === 100
        ? { items: [], total: 0, page: 1, pageSize: 100 }
        : { items: [project], total: 41, page: params.page ?? 1, pageSize: 20 },
    )
    await mount()
    const input = document.querySelector<HTMLInputElement>('.toolbar .el-input input')!
    input.value = '  Exact customer Ω  '
    input.dispatchEvent(new Event('input', { bubbles: true }))
    const select = document.querySelector<HTMLElement>('.toolbar .el-select')!
    select.click()
    await settle()
    const owner = [...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(
      (item) => item.textContent?.trim() === 'Jane Doe',
    )!
    owner.click()
    await settle()
    document.querySelector<HTMLButtonElement>('.el-pagination .btn-next')!.click()
    await settle()
    expect(presalesApi.list).toHaveBeenLastCalledWith(
      project.workspaceId,
      {
        q: '  Exact customer Ω  ',
        ownerId: project.ownerId,
        stage: '',
        page: 2,
        pageSize: 20,
      },
      expect.any(AbortSignal),
    )
    document
      .querySelector('form.toolbar')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
    expect(presalesApi.list).toHaveBeenLastCalledWith(
      project.workspaceId,
      {
        q: '  Exact customer Ω  ',
        ownerId: project.ownerId,
        stage: '',
        page: 1,
        pageSize: 20,
      },
      expect.any(AbortSignal),
    )
    document.querySelectorAll<HTMLButtonElement>('.pipeline-stage')[1]!.click()
    await settle()
    expect(presalesApi.list).toHaveBeenLastCalledWith(
      project.workspaceId,
      {
        q: '  Exact customer Ω  ',
        ownerId: project.ownerId,
        stage: 'REQUIREMENTS',
        page: 1,
        pageSize: 20,
      },
      expect.any(AbortSignal),
    )
    expect(document.querySelectorAll('.toolbar .el-select')[1]?.textContent).toContain(
      'Requirements',
    )
    document.querySelectorAll<HTMLButtonElement>('.pipeline-stage')[1]!.click()
    await settle()
    expect(presalesApi.list).toHaveBeenLastCalledWith(
      project.workspaceId,
      {
        q: '  Exact customer Ω  ',
        ownerId: project.ownerId,
        stage: '',
        page: 1,
        pageSize: 20,
      },
      expect.any(AbortSignal),
    )
  })

  it.each(['link', 'double-click'] as const)(
    'keeps ledger fallback text and exact string-ID navigation via %s',
    async (kind) => {
      const row = {
        ...project,
        stage: 'FUTURE_STAGE',
        ownerId: 'unavailable-owner',
        updatedAt: 'invalid-date',
      }
      vi.mocked(presalesApi.list).mockResolvedValue({
        items: [row],
        total: 1,
        page: 1,
        pageSize: 20,
      })
      const router = await mount()
      const ledger = document.querySelector('.project-ledger')!
      expect(ledger.textContent).toContain('Member unavailable')
      expect(ledger.textContent).toContain('FUTURE_STAGE')
      expect(ledger.textContent).toContain('Not started')
      expect(ledger.textContent).toContain('—')
      if (kind === 'link') ledger.querySelector<HTMLButtonElement>('.navigation-link')!.click()
      else
        ledger
          .querySelector('.el-table__row')!
          .dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))
      await settle()
      expect(router.currentRoute.value.params.projectId).toBe(project.id)
      expect(presalesApi.get).toHaveBeenLastCalledWith(
        project.workspaceId,
        project.id,
        expect.any(AbortSignal),
      )
    },
  )

  it('keeps the ledger load failure distinct from its empty state', async () => {
    vi.mocked(presalesApi.list).mockImplementation(async (_ws, params) => {
      if (params.pageSize === 20) throw { response: { status: 500 } }
      return { items: [], total: 0, page: 1, pageSize: 100 }
    })
    await mount()
    expect(document.querySelector('.project-ledger')?.textContent).toContain(
      'Loading failed. Retry.',
    )
    expect(document.querySelector('.project-ledger')?.textContent).not.toContain('No projects.')
  })

  it.each(['resolve', 'reject'] as const)(
    'ends loading when workspace is cleared before capabilities %s late',
    async (completion) => {
      let resolve!: (value: Awaited<ReturnType<typeof presalesApi.capabilities>>) => void
      let reject!: (reason: unknown) => void
      vi.mocked(presalesApi.capabilities).mockReturnValueOnce(
        new Promise((yes, no) => {
          resolve = yes
          reject = no
        }),
      )
      await mount(`/presales/${project.id}`)
      expect(
        document
          .querySelector('.presales-workbench')
          ?.classList.contains('el-loading-parent--relative'),
      ).toBe(true)
      workspaceFixture.current.currentWorkspaceId = ''
      await settle()
      if (completion === 'resolve')
        resolve({ enabled: true, semanticEnabled: true, canWrite: true, canApprove: true })
      else reject({ response: { status: 500 } })
      await settle()
      expect(
        document
          .querySelector('.presales-workbench')
          ?.classList.contains('el-loading-parent--relative'),
      ).toBe(false)
      expect(document.body.textContent).toContain('Select a workspace')
      expect(document.body.textContent).not.toContain(project.name)
      expect(presalesApi.get).not.toHaveBeenCalled()
      expect(presalesApi.members).not.toHaveBeenCalled()
    },
  )

  it('keeps project defaults and prevents empty required fields from reaching create', async () => {
    await mount()
    button('New project').click()
    await settle()
    const inputs = [...document.querySelectorAll<HTMLInputElement>('.el-dialog .el-input input')]
    expect(inputs[0].value).toBe('')
    expect(inputs[1].value).toBe('')
    button('Save').click()
    await settle()
    expect(document.body.textContent).toContain('Complete the required fields.')
    expect(presalesApi.create).not.toHaveBeenCalled()
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it('creates project metadata with original whitespace, string IDs and receipt', async () => {
    vi.mocked(presalesApi.create).mockResolvedValue({ ...project, version: 1 })
    await mount()
    button('New project').click()
    await settle()
    const inputs = [...document.querySelectorAll<HTMLInputElement>('.el-dialog .el-input input')]
    inputs[0].value = '  Exact project  '
    inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1].value = '  Exact customer  '
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    button('Save').click()
    await settle()
    expect(presalesApi.create).toHaveBeenCalledWith(project.workspaceId, {
      name: '  Exact project  ',
      customer: '  Exact customer  ',
      ownerId: '',
      agentId: '',
      industry: '',
      goal: '',
      expectedVersion: 0,
      operationId: expect.any(String),
    })
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it('keeps material defaults and exact source identifiers in the command payload', async () => {
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4 })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent?.includes('Materials'))!
      .click()
    await settle()
    button('Bind material').click()
    await settle()
    button('Save').click()
    await settle()
    expect(presalesApi.command).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('Complete the required fields.')
    document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
    const inputs = [...document.querySelectorAll<HTMLInputElement>('.el-dialog details input')]
    inputs[0].value = '9223372036854775800'
    inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1].value = 'graph/exact'
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(project.workspaceId, project.id, {
      action: 'BIND_MATERIAL',
      payload: { kbId: '9223372036854775800', graphId: 'graph/exact', role: 'PROJECT' },
      expectedVersion: 3,
      operationId: expect.any(String),
    })
  })

  it('keeps the latest evidence selection when two reads finish in reverse order', async () => {
    let finishFirst!: () => void
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      requirements: [
        { id: 'req-1', title: 'First', graphId: 'graph-1', evidenceIds: ['evidence-1'] },
        { id: 'req-2', title: 'Second', graphId: 'graph-1', evidenceIds: ['evidence-2'] },
      ],
    })
    vi.mocked(presalesApi.evidence)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishFirst = () => resolve({ text: 'Late first evidence' })
          }),
      )
      .mockResolvedValue({ text: 'Current second evidence' })
    await mount(`/presales/${project.id}`)
    const tab = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find((item) =>
      item.textContent?.includes('Requirements & questions'),
    )!
    tab.click()
    await settle()
    const evidenceButtons = [...document.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === 'Evidence',
    )
    evidenceButtons[0].click()
    await settle()
    evidenceButtons[1].click()
    await settle()
    expect(document.querySelector('.el-drawer')?.textContent).toContain('Current second evidence')
    finishFirst()
    await settle()
    expect(document.querySelector('.el-drawer')?.textContent).toContain('Current second evidence')
    expect(document.querySelector('.el-drawer')?.textContent).not.toContain('Late first evidence')
  })

  it.each(['success', 'error'] as const)(
    'keeps the latest presentation after a late %s from the first selection',
    async (outcome) => {
      let finishFirst!: () => void
      const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:current-preview')
      const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
      try {
        const presentation = {
          artifactId: 'artifact-1',
          skill: 'ppt-master-plus',
          skillVersion: '1.0',
          pageCount: 2,
          slides: [
            { filename: 'first.svg', title: 'First slide' },
            { filename: 'second.svg', title: 'Second slide' },
          ],
        }
        vi.mocked(presalesApi.get).mockResolvedValue({
          ...project,
          solutions: [{ id: 'solution-1', presentation }],
          tasks: [{ id: 'task-1', status: 'SUCCEEDED', result: { solution: { presentation } } }],
        })
        vi.mocked(presalesApi.file)
          .mockImplementationOnce(
            () =>
              new Promise((resolve, reject) => {
                finishFirst = () =>
                  outcome === 'success'
                    ? resolve(new Blob(['<svg>first</svg>']))
                    : reject(new Error('Late first preview failure'))
              }),
          )
          .mockResolvedValue(new Blob(['<svg>second</svg>']))
        await mount(`/presales/${project.id}`)
        button('Preview First slide').click()
        await settle()
        button('Preview Second slide').click()
        await settle()
        expect(document.querySelector('img.presentation-preview')?.getAttribute('alt')).toBe(
          'second.svg',
        )
        finishFirst()
        await settle()
        expect(document.querySelector('img.presentation-preview')?.getAttribute('alt')).toBe(
          'second.svg',
        )
        expect(document.body.textContent).not.toContain('Late first preview failure')
        expect(createUrl).toHaveBeenCalledTimes(1)
        expect(revokeUrl).not.toHaveBeenCalled()
      } finally {
        createUrl.mockRestore()
        revokeUrl.mockRestore()
      }
    },
  )

  it('does not create a presentation URL after its component is unmounted', async () => {
    let finish!: () => void
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:late-preview')
    try {
      const presentation = {
        artifactId: 'artifact-1',
        skill: 'ppt-master-plus',
        skillVersion: '1.0',
        pageCount: 2,
        slides: [{ filename: 'first.svg', title: 'First slide' }],
      }
      vi.mocked(presalesApi.get).mockResolvedValue({
        ...project,
        solutions: [{ id: 'solution-1', presentation }],
        tasks: [{ id: 'task-1', status: 'SUCCEEDED', result: { solution: { presentation } } }],
      })
      vi.mocked(presalesApi.file).mockImplementation(
        () =>
          new Promise((resolve) => {
            finish = () => resolve(new Blob(['<svg>late</svg>']))
          }),
      )
      await mount(`/presales/${project.id}`)
      button('Preview First slide').click()
      await settle()
      app!.unmount()
      app = undefined
      finish()
      await settle()
      expect(createUrl).not.toHaveBeenCalled()
    } finally {
      createUrl.mockRestore()
    }
  })

  it('does not retarget an archive confirmation after navigating to another project', async () => {
    let confirm!: () => void
    const prompt = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = () => resolve(confirmed())
        }),
    )
    try {
      const router = await mount(`/presales/${project.id}`)
      button('Archive').click()
      await settle()
      const nextProject = { ...project, id: '90071992547409996', name: 'Replacement project' }
      vi.mocked(presalesApi.get).mockResolvedValue(nextProject)
      expect(prompt).toHaveBeenCalledTimes(1)
      await router.push(`/presales/${nextProject.id}`)
      await settle()
      expect(document.body.textContent).toContain('Replacement project')
      confirm()
      await settle()
      expect(presalesApi.command).not.toHaveBeenCalled()
    } finally {
      prompt.mockRestore()
    }
  })

  it.each(['success', '409'] as const)(
    'does not close or contaminate a replacement editor after an old command %s',
    async (outcome) => {
      let finish!: () => void
      vi.mocked(presalesApi.command).mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            finish = () =>
              outcome === 'success'
                ? resolve({ ...project, version: 4 })
                : reject({ response: { status: 409, data: { msg: 'Old command conflict' } } })
          }),
      )
      await mount(`/presales/${project.id}`)
      const materials = [...document.querySelectorAll<HTMLElement>('[role="tab"]')].find((item) =>
        item.textContent?.includes('Materials'),
      )!
      materials.click()
      await settle()
      button('Bind material').click()
      await settle()
      document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
      const kbInput = document.querySelector<HTMLInputElement>('.el-dialog details input')!
      kbInput.value = 'old-kb'
      kbInput.dispatchEvent(new Event('input', { bubbles: true }))
      button('Save').click()
      await settle()
      expect(presalesApi.command).toHaveBeenCalledTimes(1)
      vi.mocked(presalesApi.get).mockResolvedValue({ ...project, workspaceId: '90071992547409995' })
      workspaceFixture.current.currentWorkspaceId = '90071992547409995'
      await settle()
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Materials'))!
        .click()
      await settle()
      button('Bind material').click()
      await settle()
      document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
      const newInput = document.querySelector<HTMLInputElement>('.el-dialog details input')!
      newInput.value = 'new-kb'
      newInput.dispatchEvent(new Event('input', { bubbles: true }))
      finish()
      await settle()
      expect(document.body.textContent).not.toContain('Old command conflict')
      expect(newInput.closest<HTMLElement>('.el-overlay')?.style.display).not.toBe('none')
      expect(newInput.value).toBe('new-kb')
      expect(button('Save').disabled).toBe(false)
    },
  )

  it('does not reuse an archive confirmation after leaving and returning to the same project', async () => {
    let confirm!: () => void
    const prompt = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = () => resolve(confirmed())
        }),
    )
    try {
      const router = await mount(`/presales/${project.id}`)
      button('Archive').click()
      await settle()
      await router.push('/presales')
      await settle()
      await router.push(`/presales/${project.id}`)
      await settle()
      confirm()
      await settle()
      expect(presalesApi.command).not.toHaveBeenCalled()
    } finally {
      prompt.mockRestore()
    }
  })

  it('keeps the confirmed target and exact version for a current archive', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(confirmed())
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4, status: 'ARCHIVED' })
    try {
      await mount(`/presales/${project.id}`)
      button('Archive').click()
      await settle()
      expect(presalesApi.command).toHaveBeenCalledWith(
        project.workspaceId,
        project.id,
        expect.objectContaining({ action: 'ARCHIVE', expectedVersion: 3, payload: {} }),
      )
      expect(button('Edit project').disabled).toBe(true)
    } finally {
      prompt.mockRestore()
    }
  })

  it('retries a failed command with the same operation receipt', async () => {
    vi.mocked(presalesApi.command)
      .mockRejectedValueOnce({
        response: { status: 500, data: { msg: 'Temporary command failure' } },
      })
      .mockResolvedValue({ ...project, version: 4 })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent?.includes('Materials'))!
      .click()
    await settle()
    button('Bind material').click()
    await settle()
    document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
    const inputs = document.querySelectorAll<HTMLInputElement>('.el-dialog details input')
    inputs[0]!.value = 'retry-kb'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1]!.value = 'retry-graph'
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    button('Save').click()
    await settle()
    expect(document.body.textContent).toContain('Temporary command failure')
    expect(button('Save').disabled).toBe(false)
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledTimes(2)
    expect(vi.mocked(presalesApi.command).mock.calls[1]).toEqual(
      vi.mocked(presalesApi.command).mock.calls[0],
    )
  })

  it('rejects an approval confirmation when polling accepts a newer revision', async () => {
    let confirm!: () => void
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = () => resolve(confirmed('Reviewed exact files'))
        }),
    )
    const active = {
      ...project,
      releases: [{ id: 'release-1', status: 'PENDING' }],
      tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
    }
    vi.mocked(presalesApi.get)
      .mockResolvedValueOnce(active)
      .mockResolvedValue({ ...active, version: 4, tasks: [] })
    try {
      await mount(`/presales/${project.id}`)
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Review & outputs'))!
        .click()
      await settle()
      button('Approve release').click()
      await settle()
      expect(prompt).toHaveBeenCalledTimes(1)
      await new Promise((resolve) => setTimeout(resolve, 1100))
      await settle()
      expect(document.body.textContent).toContain('Record version 4')
      confirm()
      await settle()
      expect(presalesApi.command).not.toHaveBeenCalled()
    } finally {
      prompt.mockRestore()
    }
  })

  it('submits the exact approval reason after the current confirmation', async () => {
    const prompt = vi
      .spyOn(ElMessageBox, 'prompt')
      .mockResolvedValue(confirmed('Approved after customer review'))
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      releases: [{ id: 'release-1', status: 'PENDING' }],
    })
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4 })
    try {
      await mount(`/presales/${project.id}`)
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Review & outputs'))!
        .click()
      await settle()
      button('Approve release').click()
      await settle()
      expect(presalesApi.command).toHaveBeenCalledWith(
        project.workspaceId,
        project.id,
        expect.objectContaining({
          action: 'APPROVE_RELEASE',
          expectedVersion: project.version,
          payload: { releaseId: 'release-1', reason: 'Approved after customer review' },
        }),
      )
    } finally {
      prompt.mockRestore()
    }
  })

  it('does not discard edits made while the unsaved-changes confirmation is pending', async () => {
    let confirm!: () => void
    const prompt = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          confirm = () => resolve(confirmed())
        }),
    )
    try {
      await mount(`/presales/${project.id}`)
      button('Edit project').click()
      await settle()
      const name = document.querySelector<HTMLInputElement>('.el-dialog input')!
      name.value = 'First draft'
      name.dispatchEvent(new Event('input', { bubbles: true }))
      const pending = workspaceFixture.beforeSwitch!()
      await settle()
      name.value = 'Updated draft'
      name.dispatchEvent(new Event('input', { bubbles: true }))
      confirm()
      expect(await pending).toBe(false)
      await settle()
      expect(name.closest<HTMLElement>('.el-overlay')?.style.display).not.toBe('none')
      expect(name.value).toBe('Updated draft')
      expect(presalesApi.update).not.toHaveBeenCalled()
    } finally {
      prompt.mockRestore()
    }
  })

  it('keeps a newer polled revision and the draft when an older command succeeds', async () => {
    let finish!: () => void
    const active = { ...project, tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }] }
    vi.mocked(presalesApi.get)
      .mockResolvedValueOnce(active)
      .mockResolvedValue({ ...project, version: 5, name: 'Latest project' })
    vi.mocked(presalesApi.command).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({ ...project, version: 4, name: 'Old command result' })
        }),
    )
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent?.includes('Materials'))!
      .click()
    await settle()
    button('Bind material').click()
    await settle()
    document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
    const kb = document.querySelector<HTMLInputElement>('.el-dialog details input')!
    kb.value = 'kb-draft'
    kb.dispatchEvent(new Event('input', { bubbles: true }))
    button('Save').click()
    await settle()
    await new Promise((resolve) => setTimeout(resolve, 1100))
    await settle()
    expect(document.body.textContent).toContain('Record version 5')
    finish()
    await settle()
    expect(document.body.textContent).toContain('Latest project')
    expect(document.body.textContent).not.toContain('Old command result')
    expect(kb.value).toBe('kb-draft')
    expect(kb.closest<HTMLElement>('.el-overlay')?.style.display).not.toBe('none')
    expect(button('Save').disabled).toBe(true)
  })

  it('does not release the replacement command busy state when an old command finishes', async () => {
    let finishOld!: () => void, finishCurrent!: () => void
    const newWorkspace = '90071992547409995'
    vi.mocked(presalesApi.command)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = () => resolve({ ...project, version: 4 })
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishCurrent = () => resolve({ ...project, workspaceId: newWorkspace, version: 4 })
          }),
      )
    await mount(`/presales/${project.id}`)
    const editMaterial = async (kbId: string) => {
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Materials'))!
        .click()
      await settle()
      button('Bind material').click()
      await settle()
      document.querySelector<HTMLDetailsElement>('.el-dialog details')!.open = true
      const input = document.querySelector<HTMLInputElement>('.el-dialog details input')!
      input.value = kbId
      input.dispatchEvent(new Event('input', { bubbles: true }))
      button('Save').click()
      await settle()
      return input
    }
    await editMaterial('old-kb')
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, workspaceId: newWorkspace })
    workspaceFixture.current.currentWorkspaceId = newWorkspace
    await settle()
    const currentInput = await editMaterial('new-kb')
    expect(presalesApi.command).toHaveBeenCalledTimes(2)
    finishOld()
    await settle()
    expect(button('Save').classList.contains('is-loading')).toBe(true)
    expect(currentInput.closest<HTMLElement>('.el-overlay')?.style.display).not.toBe('none')
    finishCurrent()
    await settle()
    expect(currentInput.closest<HTMLElement>('.el-overlay')?.style.display).toBe('none')
  })

  it.each(['success', '409'] as const)(
    'rejects an old project update %s after Workspace replacement',
    async (outcome) => {
      let finish!: () => void
      vi.mocked(presalesApi.update).mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            finish = () =>
              outcome === 'success'
                ? resolve({ ...project, version: 4, name: 'Old update result' })
                : reject({ response: { status: 409, data: { msg: 'Old update conflict' } } })
          }),
      )
      await mount(`/presales/${project.id}`)
      button('Edit project').click()
      await settle()
      const oldInput = document.querySelector<HTMLInputElement>('.el-dialog input')!
      oldInput.value = 'Old edit'
      oldInput.dispatchEvent(new Event('input', { bubbles: true }))
      button('Save').click()
      await settle()
      expect(presalesApi.update).toHaveBeenCalledTimes(1)
      vi.mocked(presalesApi.get).mockResolvedValue({
        ...project,
        workspaceId: '90071992547409995',
        name: 'Current project',
      })
      workspaceFixture.current.currentWorkspaceId = '90071992547409995'
      await settle()
      button('Edit project').click()
      await settle()
      const newInput = document.querySelector<HTMLInputElement>('.el-dialog input')!
      newInput.value = 'Current draft'
      newInput.dispatchEvent(new Event('input', { bubbles: true }))
      finish()
      await settle()
      expect(document.body.textContent).toContain('Current project')
      expect(document.body.textContent).not.toContain('Old update conflict')
      expect(document.body.textContent).not.toContain('Old update result')
      expect(newInput.closest<HTMLElement>('.el-overlay')?.style.display).not.toBe('none')
      expect(newInput.value).toBe('Current draft')
      expect(button('Save').disabled).toBe(false)
    },
  )

  it('runs the real route-leave guard and retains a dirty draft when discard is cancelled', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    try {
      const router = await mount(`/presales/${project.id}`)
      button('Edit project').click()
      await settle()
      const name = document.querySelector<HTMLInputElement>('.el-dialog input')!
      name.value = 'Retained draft'
      name.dispatchEvent(new Event('input', { bubbles: true }))
      await router.push('/outside')
      await settle()
      expect(confirm).toHaveBeenCalledTimes(1)
      expect(router.currentRoute.value.path).toBe(`/presales/${project.id}`)
      expect(name.value).toBe('Retained draft')
      expect(name.closest<HTMLElement>('.el-overlay')?.style.display).not.toBe('none')
      expect(document.body.textContent).not.toContain('Outside page')
    } finally {
      confirm.mockRestore()
    }
  })

  it.each(['success', 'error'] as const)(
    'keeps reopened material options after an old options %s',
    async (outcome) => {
      let finish!: () => void
      vi.mocked(presalesApi.sources)
        .mockImplementationOnce(
          () =>
            new Promise((resolve, reject) => {
              finish = () =>
                outcome === 'success'
                  ? resolve([{ kbId: 'old-kb', name: 'Old source option' }])
                  : reject(new Error('Old options failure'))
            }),
        )
        .mockResolvedValue([{ kbId: 'current-kb', name: 'Current source option' }])
      await mount(`/presales/${project.id}`)
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Materials'))!
        .click()
      await settle()
      button('Bind material').click()
      await settle()
      button('Cancel').click()
      await settle()
      button('Bind material').click()
      await settle()
      expect(presalesApi.sources).toHaveBeenCalledTimes(2)
      document.querySelector<HTMLElement>('.el-dialog .el-select__wrapper')!.click()
      await settle()
      expect(document.body.textContent).toContain('Current source option')
      finish()
      await settle()
      expect(document.body.textContent).toContain('Current source option')
      expect(document.body.textContent).not.toContain('Old source option')
      expect(document.body.textContent).not.toContain('Old options failure')
    },
  )

  it('recovers a member repair view after full read denial and unbinds only the opaque binding', async () => {
    vi.mocked(presalesApi.get).mockRejectedValue({
      response: { status: 403, data: { msg: 'Source access denied' } },
    })
    vi.mocked(presalesApi.repairContext).mockResolvedValue({
      ...repairProject,
      repairBindings: [{ id: 'binding-1', role: 'PROJECT' }],
    })
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4 })
    await mount(`/presales/${project.id}`)
    expect(presalesApi.repairContext).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.any(AbortSignal),
    )
    expect(document.body.textContent).toContain('Source access is restricted')
    expect(button('Delegate to employee').disabled).toBe(true)
    expect(button('Assign employee').disabled).toBe(false)
    button('Unbind material').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({
        action: 'UNBIND_MATERIAL',
        payload: { id: 'binding-1' },
        expectedVersion: 3,
      }),
    )
    expect(document.body.textContent).not.toContain('Source access is restricted')
  })

  it('does not request repair metadata for a read-only viewer', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({
      enabled: true,
      semanticEnabled: true,
      canWrite: false,
      canApprove: false,
    })
    vi.mocked(presalesApi.get).mockRejectedValue({
      response: { status: 403, data: { msg: 'Source access denied' } },
    })
    await mount(`/presales/${project.id}`)
    expect(presalesApi.repairContext).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('Source access denied')
    expect(button('Edit project').disabled).toBe(true)
  })

  it('saves a restricted employee assignment without unrelated project fields', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, sourceAccessRestricted: true })
    vi.mocked(presalesApi.employees).mockResolvedValue([
      { id: 'employee-2', name: 'Repair employee', enabled: true, available: true },
    ])
    vi.mocked(presalesApi.command).mockResolvedValue({
      ...project,
      version: 4,
      agentId: 'employee-2',
    })
    await mount(`/presales/${project.id}`)
    button('Assign employee').click()
    await settle()
    const wrapper = document.querySelector('.el-dialog .el-select__wrapper') as HTMLElement
    wrapper.click()
    await settle()
    const option = [...document.querySelectorAll('.el-select-dropdown__item')].find((item) =>
      item.textContent?.includes('Repair employee'),
    ) as HTMLElement
    option.click()
    await settle()
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({ action: 'UPDATE_PROJECT', payload: { agentId: 'employee-2' } }),
    )
    expect(presalesApi.update).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('Source access is restricted')
  })

  it('ignores a repair view arriving after navigation to another project', async () => {
    let finish!: (value: PresalesRepairContext) => void
    vi.mocked(presalesApi.get).mockRejectedValueOnce({ response: { status: 403 } })
    vi.mocked(presalesApi.repairContext).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const router = await mount(`/presales/${project.id}`)
    await router.push('/presales/next-project')
    await settle()
    finish({ ...repairProject, name: 'Old repair context' })
    await settle()
    expect(document.body.textContent).not.toContain('Old repair context')
    expect(document.body.textContent).not.toContain('Source access is restricted')
  })

  it('rejects source-rich or cross-project repair responses', async () => {
    vi.mocked(presalesApi.get).mockRejectedValue({
      response: { status: 403, data: { msg: 'Source access denied' } },
    })
    vi.mocked(presalesApi.repairContext).mockResolvedValue(
      Object.assign({ ...repairProject }, { context: { text: 'Leaked context' } }),
    )
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).not.toContain('Leaked context')
    expect(document.body.textContent).toContain('Source access denied')
    expect(button('Edit project').disabled).toBe(true)
  })

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
    expect(button('Edit project').disabled).toBe(true)
    expect(button('Archive').disabled).toBe(true)
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
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({
        action: 'BIND_MATERIAL',
        payload: { kbId: 'kb-1', graphId: '', role: 'PROJECT' },
      }),
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

  it('ignores a cancelled same-project poll that resolves after a restricted reload', async () => {
    const active = {
      ...project,
      context: { text: 'Old authorized content' },
      tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
    }
    let finish!: () => void
    vi.mocked(presalesApi.get)
      .mockResolvedValueOnce(active)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = () => resolve(active)
          }),
      )
      .mockRejectedValue({
        response: { status: 403, data: { msg: 'Source denied' } },
      })
    vi.mocked(presalesApi.repairContext).mockResolvedValue(repairProject)
    vi.mocked(presalesApi.cancelTask).mockRejectedValue(new Error('Reload needed'))
    await mount(`/presales/${project.id}`)
    await new Promise((resolve) => setTimeout(resolve, 1100))
    await settle()
    const oldSignal = vi.mocked(presalesApi.get).mock.calls[1][2]!
    expect(oldSignal.aborted).toBe(false)
    button('Discard this run result').click()
    await settle()
    button('Reload').click()
    await settle()
    expect(oldSignal.aborted).toBe(true)
    expect(document.body.textContent).toContain('Source access is restricted')
    finish()
    await settle()
    expect(document.body.textContent).toContain('Source access is restricted')
    expect(document.body.textContent).not.toContain('Old authorized content')
    expect(button('Edit project').disabled).toBe(true)
  })

  it.each(['success', '403'] as const)(
    'keeps a newer cancellation response after a late poll %s',
    async (outcome) => {
      const active = {
        ...project,
        context: { text: 'Old authorized content' },
        tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
      }
      let finish!: () => void
      vi.mocked(presalesApi.get)
        .mockResolvedValueOnce(active)
        .mockImplementationOnce(
          () =>
            new Promise((resolve, reject) => {
              finish = () =>
                outcome === 'success'
                  ? resolve(active)
                  : reject({
                      response: { status: 403, data: { msg: 'Late denied' } },
                    })
            }),
        )
        .mockResolvedValue({ ...project, version: 4, tasks: [] })
      vi.mocked(presalesApi.cancelTask).mockResolvedValue({
        ...project,
        version: 4,
        context: { text: 'Current cancellation state' },
        tasks: [{ id: 'task-1', operationId: 'op-1', status: 'CANCELLED' }],
      })
      await mount(`/presales/${project.id}`)
      await new Promise((resolve) => setTimeout(resolve, 1100))
      await settle()
      button('Discard this run result').click()
      await settle()
      expect(document.body.textContent).toContain('Current cancellation state')
      finish()
      await settle()
      expect(document.body.textContent).toContain('Current cancellation state')
      expect(document.body.textContent).not.toContain('Old authorized content')
      expect(document.body.textContent).not.toContain('Discard this run result')
      expect(presalesApi.repairContext).not.toHaveBeenCalled()
      expect(document.body.textContent).not.toContain('Late denied')
    },
  )

  it('uses one project read to poll multiple running operations', async () => {
    vi.mocked(presalesApi.get)
      .mockResolvedValueOnce({
        ...project,
        tasks: [
          { id: 'task-1', operationId: 'op-1', status: 'RUNNING' },
          { id: 'task-2', operationId: 'op-2', status: 'RUNNING' },
        ],
      })
      .mockResolvedValue({ ...project, version: 4, tasks: [] })
    await mount(`/presales/${project.id}`)
    await new Promise((resolve) => setTimeout(resolve, 1100))
    await settle()
    expect(presalesApi.get).toHaveBeenCalledTimes(2)
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
            finish = () =>
              resolve({
                id: 'handoff',
                text: 'Private handoff',
                schemaVersion: 1,
                workspaceId: project.workspaceId,
                engagementId: project.id,
                caseRef: project.id,
                baseline: { id: 'baseline-1' },
                solution: { id: 'solution-1' },
                release: { id: 'release-1' },
                fitGaps: [],
                clarifications: [],
                risksAndUnknowns: [],
                customerConfirmationStatus: 'UNCONFIRMED',
                accessPolicy: 'WORKSPACE_REAUTHORIZE_ON_READ',
              })
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

  it.each(['files', 'preview'] as const)(
    'downloads a %s artifact with the original Blob and filename',
    async (kind) => {
      const filename = 'solution.pdf'
      const blob = new Blob([`exact-${kind}`], { type: 'application/pdf' })
      const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue(`blob:${kind}`)
      const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
      const clicked: HTMLAnchorElement[] = []
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
        this: HTMLAnchorElement,
      ) {
        clicked.push(this)
      })
      try {
        vi.mocked(presalesApi.get).mockResolvedValue({
          ...project,
          releases: [
            {
              id: 'release-1',
              status: kind === 'files' ? 'PUBLISHED' : 'PENDING',
              files: [{ filename }],
            },
          ],
        })
        vi.mocked(presalesApi.file).mockResolvedValue(blob)
        await mount(`/presales/${project.id}`)
        ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
          .find((item) => item.textContent?.includes('Review & outputs'))!
          .click()
        await settle()
        button(`${kind === 'files' ? 'Download' : 'Unapproved preview'} ${filename}`).click()
        await settle()
        expect(presalesApi.file).toHaveBeenCalledWith(
          project.workspaceId,
          project.id,
          'release-1',
          filename,
          kind,
        )
        expect(createUrl).toHaveBeenCalledWith(blob)
        expect(clicked[0]?.href).toBe(`blob:${kind}`)
        expect(clicked[0]?.download).toBe(kind === 'files' ? filename : `UNAPPROVED-${filename}`)
        await new Promise((resolve) => setTimeout(resolve, 1100))
        expect(revokeUrl).toHaveBeenCalledWith(`blob:${kind}`)
      } finally {
        click.mockRestore()
        createUrl.mockRestore()
        revokeUrl.mockRestore()
      }
    },
  )

  it('downloads a draft with the unapproved filename and releases its URL', async () => {
    const blob = new Blob(['draft-bytes'], { type: 'text/markdown' })
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:draft')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clicked: HTMLAnchorElement[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this)
    })
    try {
      vi.mocked(presalesApi.get).mockResolvedValue({
        ...project,
        solutions: [
          { id: 'solution-1', title: 'Draft', sections: [{ title: 'Scope', text: 'draft' }] },
        ],
      })
      vi.mocked(presalesApi.file).mockResolvedValue(blob)
      await mount(`/presales/${project.id}`)
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Solution design'))!
        .click()
      await settle()
      document.querySelector<HTMLButtonElement>('.solution-downloads button')!.click()
      await settle()
      expect(presalesApi.file).toHaveBeenCalledWith(
        project.workspaceId,
        project.id,
        'solution-1',
        'solution.md',
        'draft',
      )
      expect(createUrl).toHaveBeenCalledWith(blob)
      expect(clicked[0]?.href).toBe('blob:draft')
      expect(clicked[0]?.download).toBe('UNAPPROVED-solution.md')
      await new Promise((resolve) => setTimeout(resolve, 1100))
      expect(revokeUrl).toHaveBeenCalledWith('blob:draft')
    } finally {
      click.mockRestore()
      createUrl.mockRestore()
      revokeUrl.mockRestore()
    }
  })

  it('exports the exact handoff payload with the current project version', async () => {
    const handoff: PresalesHandoff = {
      id: 'handoff-1',
      text: 'Internal handoff',
      schemaVersion: 1,
      workspaceId: project.workspaceId,
      engagementId: project.id,
      caseRef: project.id,
      baseline: { id: 'baseline-1' },
      solution: { id: 'solution-1' },
      release: { id: 'release-1' },
      fitGaps: [],
      clarifications: [],
      risksAndUnknowns: [],
      customerConfirmationStatus: 'UNCONFIRMED',
      accessPolicy: 'WORKSPACE_REAUTHORIZE_ON_READ',
    }
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:handoff')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clicked: HTMLAnchorElement[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this)
    })
    try {
      vi.mocked(presalesApi.get).mockResolvedValue({
        ...project,
        version: 8,
        releases: [{ id: 'release-1', status: 'PUBLISHED', files: [] }],
      })
      vi.mocked(presalesApi.handoff).mockResolvedValue(handoff)
      await mount(`/presales/${project.id}`)
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent?.includes('Review & outputs'))!
        .click()
      await settle()
      button('Export internal handoff').click()
      await settle()
      expect(presalesApi.handoff).toHaveBeenCalledWith(project.workspaceId, project.id)
      const handoffBlob = vi.mocked(createUrl).mock.calls[0]?.[0] as Blob
      expect(await handoffBlob.text()).toBe(JSON.stringify(handoff, null, 2))
      expect(clicked[0]?.download).toBe(`internal-handoff-${project.id}-v8.json`)
      expect(clicked[0]?.href).toBe('blob:handoff')
      await new Promise((resolve) => setTimeout(resolve, 1100))
      expect(revokeUrl).toHaveBeenCalledWith('blob:handoff')
    } finally {
      click.mockRestore()
      createUrl.mockRestore()
      revokeUrl.mockRestore()
    }
  })

  it.each(['pending', 'opened'] as const)(
    'clears %s presentation previews and releases their object URL on restriction',
    async (state) => {
      let finish!: () => void
      const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:private-preview')
      const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
      try {
        vi.mocked(presalesApi.file).mockReturnValue(
          new Promise((resolve) => {
            finish = () => resolve(new Blob(['<svg>private</svg>']))
          }),
        )
        const presentation = {
          artifactId: 'artifact-1',
          skill: 'ppt-master-plus',
          skillVersion: '1.0',
          pageCount: 1,
          slides: [{ filename: 'slide.svg', title: 'Private slide' }],
        }
        vi.mocked(presalesApi.get)
          .mockResolvedValueOnce({
            ...project,
            solutions: [{ id: 'solution-1', presentation }],
            tasks: [
              {
                id: 'task-1',
                operationId: 'op-1',
                status: 'RUNNING',
                result: { solution: { presentation } },
              },
            ],
          })
          .mockResolvedValue({ ...project, sourceAccessRestricted: true })
        await mount(`/presales/${project.id}`)
        button('Preview Private slide').click()
        await settle()
        if (state === 'opened') {
          finish()
          await settle()
          expect(document.querySelector('img[src="blob:private-preview"]')).not.toBeNull()
          expect(createUrl).toHaveBeenCalledTimes(1)
        }
        await new Promise((resolve) => setTimeout(resolve, 1100))
        await settle()
        if (state === 'pending') {
          finish()
          await settle()
          expect(createUrl).not.toHaveBeenCalled()
        } else expect(revokeUrl).toHaveBeenCalledWith('blob:private-preview')
        expect(document.querySelector('img[src="blob:private-preview"]')).toBeNull()
      } finally {
        createUrl.mockRestore()
        revokeUrl.mockRestore()
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
  it.each(['create', 'update'] as const)(
    'retains the %s draft and blocks blind resubmission of an unverifiable legacy receipt',
    async (action) => {
      await mount(action === 'create' ? '/presales' : `/presales/${project.id}`)
      button(action === 'create' ? 'New project' : 'Edit project').click()
      await settle()
      const inputs = document.querySelectorAll<HTMLInputElement>('.el-dialog input')
      inputs[0].value = 'Keep my draft?'
      inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
      if (action === 'create') {
        inputs[1].value = 'Customer'
        inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
      }
      const message = 'Stored operation cannot be safely verified; review before retrying'
      const request = vi.mocked(presalesApi[action])
      request.mockRejectedValue({
        response: {
          status: 409,
          data: { msg: message, data: { code: 'OPERATION_REPLAY_UNVERIFIABLE' } },
        },
      })
      button('Save').click()
      await settle()
      expect(request).toHaveBeenCalledTimes(1)
      expect(inputs[0].value).toBe('Keep my draft?')
      expect(document.body.textContent).toContain(message)
      expect(button('Save').disabled).toBe(true)
      button('Save').click()
      await settle()
      expect(request).toHaveBeenCalledTimes(1)
    },
  )
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
  it('keeps the unanswered summary consistent while OPEN excludes unknown states', async () => {
    const legacy = {
      ...project,
      clarifications: [{ id: 'unknown', question: 'Unknown clarification', status: 'RUNNING' }],
    }
    vi.mocked(presalesApi.list).mockResolvedValue({
      items: [{ ...project, openClarificationCount: 1 }],
      total: 1,
      page: 1,
      pageSize: 20,
    })
    vi.mocked(presalesApi.get).mockResolvedValue(legacy)
    const router = await mount()
    const metric = [...document.querySelectorAll('.metric')].find((item) =>
      item.textContent?.includes('Unanswered'),
    )!
    expect(metric).toBeDefined()
    expect(metric.querySelector('strong')?.textContent).toContain('1')
    expect(document.querySelector('.project-ledger')?.textContent).toContain('Unanswered')
    await router.push(`/presales/${project.id}`)
    await settle()
    expect(document.body.textContent).toContain('1 unanswered')
    document.querySelector<HTMLElement>('#tab-requirements')!.click()
    await settle()
    expect(
      document.querySelector('[aria-label="Filter clarification status"]')?.textContent,
    ).toContain('Open 0')
    expect(document.body.textContent).toContain('Unknown status: RUNNING')
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it('keeps cross-domain and missing clarification states out of the OPEN filter', async () => {
    const clarifications = [
      { id: 'open', question: 'Known open', status: 'OPEN' },
      { id: 'answered', question: 'Known answered', status: 'ANSWERED' },
      { id: 'cross', question: 'Foreign lifecycle', status: 'RUNNING' },
      { id: 'legacy', question: 'Legacy lifecycle', status: ' OLD ' },
      { id: 'missing', question: 'Missing lifecycle' },
    ]
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, clarifications })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent === 'Requirements & questions')!
      .click()
    await settle()
    expect(document.body.textContent).toContain('Unknown status: RUNNING')
    expect(document.body.textContent).toContain('Unknown status:  OLD ')
    const filter = document.querySelector('[aria-label="Filter clarification status"]')!
    const open = [...filter.querySelectorAll<HTMLElement>('.el-radio-button')].find(
      (item) => item.textContent?.trim() === 'Open 1',
    )!
    expect(open).toBeDefined()
    open.click()
    await settle()
    expect(document.body.textContent).toContain('Known open')
    expect(document.body.textContent).not.toContain('Foreign lifecycle')
    expect(document.body.textContent).not.toContain('Missing lifecycle')
    expect(document.body.textContent).not.toContain('Legacy lifecycle')
    const all = [...filter.querySelectorAll<HTMLElement>('.el-radio-button')].find(
      (item) => item.textContent?.trim() === 'All 5',
    )!
    all.click()
    await settle()
    expect(document.body.textContent).toContain('Foreign lifecycle')
    expect(document.body.textContent).toContain('Missing lifecycle')
    expect(clarifications[2].status).toBe('RUNNING')
    expect(presalesApi.command).not.toHaveBeenCalled()
  })
  it('does not treat a clarification state as a known task state or enable cancellation', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      tasks: [{ id: 'task-foreign', status: 'ANSWERED', skill: 'S1', operationId: 'opaque' }],
    })
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Unknown status: ANSWERED')
    expect(presalesApi.cancelTask).not.toHaveBeenCalled()
    expect(
      [...document.querySelectorAll('button')].some(
        (item) => item.textContent?.trim() === 'Discard this run result' && !item.disabled,
      ),
    ).toBe(false)
    expect(presalesApi.get).toHaveBeenCalledTimes(1)
  })

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
      { id: '90071992547409996', name: 'Presales specialist', enabled: true, available: true },
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

describe('solution and output presentation contracts', () => {
  it('keeps solution empty-state guidance and generation authority', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({
      enabled: true,
      semanticEnabled: true,
      canWrite: false,
      canApprove: false,
    })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent === 'Solution design')!
      .click()
    await settle()
    expect(button('Ask employee to compose').disabled).toBe(true)
    expect(document.querySelectorAll('.solution-revision')).toHaveLength(0)
    expect(document.querySelector('.el-empty')).not.toBeNull()
  })

  it('preserves newest-first solution versions, coverage, safe text and original draft intents', async () => {
    const unsafe = '<img src=x onerror=alert(1)>'
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      requirements: [
        { id: 'req-in', title: 'Covered requirement', scope: 'IN' },
        { id: 'req-out', title: 'Excluded requirement', scope: 'OUT' },
      ],
      baselines: [
        {
          id: 'baseline-1',
          references: [
            { requirementId: 'req-in', scope: 'IN' },
            { requirementId: 'req-out', scope: 'OUT' },
          ],
        },
      ],
      fitGaps: [{ id: 'fit-1', requirementId: 'req-in', status: 'FIT' }],
      solutions: [
        {
          id: '90071992547409991',
          title: 'Previous',
          sections: [{ title: 'Scope', text: 'Before' }],
        },
        {
          id: '90071992547409992',
          title: 'Latest',
          baselineId: 'baseline-1',
          coverage: {
            applicable: true,
            handledIn: 1,
            totalIn: 1,
            responses: [{ requirementId: 'req-in', status: 'FULL' }],
          },
          sections: [{ title: 'Scope', text: unsafe, requirementRefs: ['req-in'] }],
        },
      ],
    })
    vi.mocked(presalesApi.file).mockRejectedValue({
      response: { status: 403 },
    })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent === 'Solution design')!
      .click()
    await settle()
    const versions = [...document.querySelectorAll('.solution-revision')]
    expect(versions.map((item) => item.querySelector('h3')?.textContent?.trim())).toEqual([
      'Latest · V2',
      'Previous · V1',
    ])
    expect(versions[0]!.querySelector('pre')?.textContent).toBe(unsafe)
    expect(versions[0]!.querySelector('img')).toBeNull()
    expect(document.querySelector('.coverage')?.textContent).toContain('1/1')
    expect(document.querySelector('.coverage')?.textContent).toContain('Full')
    expect(document.querySelector('.coverage')?.textContent).toContain('Unhandled')
    const compareSelects = document.querySelectorAll<HTMLElement>(
      '#pane-solution .toolbar .el-select',
    )
    compareSelects[0]!.click()
    await settle()
    const option = [...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(
      (item) => item.textContent?.trim() === 'Previous · V1',
    )!
    option.click()
    await settle()
    expect(document.querySelector('#pane-solution')?.textContent).toContain('Before')
    expect(document.querySelectorAll('#pane-solution pre.safe-content')).toHaveLength(4)
    compareSelects[1]!.click()
    await settle()
    const targetOption = [...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')]
      .filter((item) => item.textContent?.trim() === 'Previous · V1')
      .at(-1)!
    targetOption.click()
    await settle()
    expect(
      [...document.querySelectorAll('#pane-solution pre.safe-content')].filter(
        (item) => item.textContent === 'Before',
      ),
    ).toHaveLength(3)
    changeLocale('zh-CN')
    await settle()
    expect(document.querySelector('#pane-solution')?.textContent).toContain('需求响应覆盖')
    ;(versions[0]!.querySelector('.solution-downloads button') as HTMLButtonElement).click()
    await settle()
    expect(presalesApi.file).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      '90071992547409992',
      'solution.md',
      'draft',
    )
  })

  it.each([false, true])(
    'keeps published/preview/approval/publish availability with canApprove=%s',
    async (canApprove) => {
      vi.mocked(presalesApi.capabilities).mockResolvedValue({
        enabled: true,
        semanticEnabled: true,
        canWrite: true,
        canApprove,
      })
      vi.mocked(presalesApi.get).mockResolvedValue({
        ...project,
        reviews: [
          {
            id: 'review-1',
            solutionId: 'missing',
            summary: 'Checked',
            issues: [{ title: '<script>unsafe</script>' }],
          },
        ],
        releases: ['PENDING', 'APPROVED', 'PUBLISHED'].map((status, i) => ({
          id: `release-${i}`,
          status,
          files: [{ filename: `file-${i}.pdf` }],
        })),
      })
      await mount(`/presales/${project.id}`)
      ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
        .find((item) => item.textContent === 'Review & outputs')!
        .click()
      await settle()
      expect(button('Unapproved preview file-0.pdf').disabled).toBe(!canApprove)
      expect(button('Unapproved preview file-1.pdf').disabled).toBe(!canApprove)
      expect(button('Download file-2.pdf').disabled).toBe(false)
      const actions = [...document.querySelectorAll('.table-actions')]
      const named = (row: Element, label: string) =>
        [...row.querySelectorAll('button')].find((item) => item.textContent?.trim() === label)!
      expect(actions.map((row) => named(row, 'Approve release').disabled)).toEqual([
        !canApprove,
        true,
        true,
      ])
      expect(actions.map((row) => named(row, 'Publish').disabled)).toEqual([
        true,
        !canApprove,
        true,
      ])
      expect(document.querySelectorAll('script')).toHaveLength(0)
      expect(document.body.textContent).toContain('<script>unsafe</script>')
      expect(presalesApi.command).not.toHaveBeenCalled()
      changeLocale('zh-CN')
      await settle()
      expect(document.querySelector('#pane-review')?.textContent).toContain('成果版本')
    },
  )
})

describe('editor navigation and unload protection', () => {
  it('protects a dirty draft on unload and removes the listener after unmount', async () => {
    await mount()
    button('New project').click()
    await settle()
    const name = document.querySelector<HTMLInputElement>('[role="dialog"] input')!
    name.value = 'Unsaved project'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    const before = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(before)
    expect(before.defaultPrevented).toBe(true)
    app!.unmount()
    app = undefined
    const after = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(after)
    expect(after.defaultPrevented).toBe(false)
  })

  it('uses the route-update guard to retain a cancelled draft on the same workbench route', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    try {
      const router = await mount(`/presales/${project.id}`)
      button('Edit project').click()
      await settle()
      const name = document.querySelector<HTMLInputElement>('[role="dialog"] input')!
      name.value = 'Retain on route update'
      name.dispatchEvent(new Event('input', { bubbles: true }))
      await settle()
      await router.push('/presales/90071992547409996')
      await settle()
      expect(router.currentRoute.value.params.projectId).toBe(project.id)
      expect(name.value).toBe('Retain on route update')
      expect(confirm).toHaveBeenCalledTimes(1)
    } finally {
      confirm.mockRestore()
    }
  })
})

describe('editor field presentation contracts', () => {
  it('keeps project field limits, required marks and host locale changes', async () => {
    await mount()
    button('New project').click()
    await settle()
    const form = document.querySelector('.el-dialog .el-form')!
    const inputs = form.querySelectorAll('input[maxlength="200"]')
    expect(inputs).toHaveLength(2)
    expect(form.querySelectorAll('.el-form-item.is-required')).toHaveLength(2)
    changeLocale('zh-CN')
    await settle()
    expect(form.textContent).toContain('项目名称')
    expect(form.textContent).toContain('客户')
  })
  it('submits confirmed requirements with the original reason and exact version', async () => {
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4 })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent === 'Requirements & questions')!
      .click()
    await settle()
    button('Confirm requirements').click()
    await settle()
    const reason = document.querySelector<HTMLTextAreaElement>('.el-dialog textarea')!
    expect(reason.getAttribute('rows')).toBe('5')
    changeLocale('zh-CN')
    await settle()
    expect(document.querySelector('.el-dialog')?.textContent).toContain('批准理由、条件与责任人')
    changeLocale('en-US')
    await settle()
    reason.value = ' Customer confirmed exact scope '
    reason.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(project.workspaceId, project.id, {
      action: 'APPROVE_BASELINE',
      payload: { reason: ' Customer confirmed exact scope ' },
      expectedVersion: 3,
      operationId: expect.any(String),
    })
  })

  it('edits and adds solution sections in the same version-protected draft', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      requirements: [{ id: 'req-1', title: 'Confirmed requirement' }],
      baselines: [{ id: 'b1', references: [{ requirementId: 'req-1' }] }],
      solutions: [
        {
          id: '90071992547409991',
          title: 'Latest',
          baselineId: 'b1',
          sections: [{ title: 'Original section', text: 'Body', requirementRefs: ['req-1'] }],
        },
      ],
    })
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4 })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent === 'Solution design')!
      .click()
    await settle()
    button('Revise this version').click()
    await settle()
    const dialog = document.querySelector('.el-dialog .el-form')!.closest('.el-dialog')!
    changeLocale('zh-CN')
    await settle()
    expect(dialog.textContent).toContain('方案标题')
    changeLocale('en-US')
    await settle()
    const title = dialog.querySelector<HTMLInputElement>('.section-editor input')!
    title.value = 'Updated section'
    title.dispatchEvent(new Event('input', { bubbles: true }))
    button('Add section').click()
    await settle()
    expect(dialog.querySelectorAll('.section-editor')).toHaveLength(3)
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({
        action: 'SAVE_SOLUTION',
        expectedVersion: 3,
        operationId: expect.any(String),
        payload: expect.objectContaining({
          title: 'Latest',
          baselineId: 'b1',
          sections: [
            { title: 'Updated section', text: 'Body', requirementRefs: ['req-1'] },
            { title: '', text: '', requirementRefs: [] },
          ],
          requirementResponses: [{ requirementId: 'req-1', status: 'UNHANDLED', reason: '' }],
        }),
      }),
    )
  })

  it('adds review findings and keeps exact solution selection and original issue defaults', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      solutions: [{ id: '90071992547409991', title: 'Latest', sections: [] }],
    })
    vi.mocked(presalesApi.command).mockResolvedValue({ ...project, version: 4 })
    await mount(`/presales/${project.id}`)
    ;[...document.querySelectorAll<HTMLElement>('[role="tab"]')]
      .find((item) => item.textContent === 'Review & outputs')!
      .click()
    await settle()
    button('Record independent review').click()
    await settle()
    const dialog = document.querySelector('.el-dialog .el-form')!.closest('.el-dialog')!
    ;(dialog.querySelector('.el-select') as HTMLElement).click()
    await settle()
    ;[...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')]
      .find((item) => item.textContent?.trim() === 'Latest · V1')!
      .click()
    const summary = dialog.querySelector<HTMLTextAreaElement>('textarea')!
    summary.value = 'Reviewed exact solution'
    summary.dispatchEvent(new Event('input', { bubbles: true }))
    button('Add finding').click()
    await settle()
    const issue = dialog.querySelector<HTMLTextAreaElement>('.section-editor textarea')!
    issue.value = 'Evidence needed'
    issue.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    button('Save').click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(
      project.workspaceId,
      project.id,
      expect.objectContaining({
        action: 'SAVE_REVIEW',
        expectedVersion: 3,
        operationId: expect.any(String),
        payload: {
          solutionId: '90071992547409991',
          summary: 'Reviewed exact solution',
          issues: [{ description: 'Evidence needed', severity: 'WARNING', status: 'OPEN' }],
        },
      }),
    )
  })
})

describe('overview task presentation contracts', () => {
  it('keeps latest context, baseline, safe proposal text and task snapshot selection', async () => {
    const task = {
      id: '90071992547409990',
      status: 'SUCCEEDED',
      skill: 'S1',
      contextSnapshot: { truncated: true, inputs: ['exact input'] },
      result: {
        items: [
          { title: 'Context proposal', text: '<script>unsafe()</script>', originKind: 'INFERRED' },
        ],
        unknowns: ['Open question'],
        assumptions: ['Pending confirmation'],
      },
    }
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      context: 'Old context',
      contextCards: [{ id: 'context-latest', text: 'Latest context' }],
      baselines: [{ id: '90071992547409989' }],
      tasks: [task],
    })
    await mount(`/presales/${project.id}`)
    const overview = document.querySelector('[id$="pane-overview"]')!
    expect(overview.textContent).toContain('Latest context')
    expect(overview.textContent).not.toContain('Old context')
    expect(overview.textContent).toContain('90071992547409989')
    expect(overview.textContent).toContain('<script>unsafe()</script>')
    expect(overview.querySelector('script')).toBeNull()
    expect(overview.querySelector('.el-alert')).not.toBeNull()
    expect(overview.textContent).toContain('Open question')
    button('Input snapshot').click()
    await settle()
    expect(document.querySelector('.el-drawer')?.textContent).toContain('exact input')
    button('Review proposal').click()
    await settle()
    expect(document.querySelector<HTMLTextAreaElement>('.el-dialog textarea')?.value).toBe(
      '<script>unsafe()</script>',
    )
    expect(presalesApi.command).not.toHaveBeenCalled()
    changeLocale('zh-CN')
    await settle()
    expect(overview.textContent).toContain('员工')
  })

  it('keeps queued cancellation on the exact string task and accepts the returned version', async () => {
    const task = { id: '90071992547409990', status: 'RUNNING', skill: 'S1', queueState: 'QUEUED' }
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, tasks: [task] })
    vi.mocked(presalesApi.cancelTask).mockResolvedValue({
      ...project,
      version: 4,
      tasks: [{ ...task, status: 'CANCELLED' }],
    })
    await mount(`/presales/${project.id}`)
    const overview = document.querySelector('[id$="pane-overview"]')!
    expect(overview.textContent).toContain('Accepted and waiting for the employee')
    button('Discard this run result').click()
    await settle()
    expect(presalesApi.cancelTask).toHaveBeenCalledWith(project.workspaceId, project.id, task.id, {
      operationId: expect.any(String),
    })
    expect(overview.textContent).not.toContain('Discard this run result')
    expect(overview.textContent).not.toContain('Accepted and waiting for the employee')
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it('keeps task write actions disabled for a read-only viewer', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({
      enabled: true,
      semanticEnabled: true,
      canWrite: false,
      canApprove: false,
    })
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      tasks: [
        {
          id: 'task-readonly',
          status: 'RUNNING',
          skill: 'S6',
          result: { items: [{ title: 'Proposal', text: 'Readonly draft' }] },
        },
      ],
    })
    await mount(`/presales/${project.id}`)
    expect(button('Discard this run result').disabled).toBe(true)
    expect(button('Review proposal').disabled).toBe(true)
    expect(button('Ask employee to analyze').disabled).toBe(true)
    button('Input snapshot').click()
    await settle()
    expect(document.querySelector('.el-drawer')?.textContent).toContain('task-readonly')
    expect(presalesApi.cancelTask).not.toHaveBeenCalled()
    expect(presalesApi.command).not.toHaveBeenCalled()
  })
})

describe('status display boundary', () => {
  it('keeps existing known labels without rewriting project state', async () => {
    const detail = {
      ...project,
      stage: 'DISCOVERY',
      tasks: [{ id: 'status-task', status: 'SUCCEEDED', skill: 'S1' }],
    }
    vi.mocked(presalesApi.get).mockResolvedValue(detail)
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain('Discovery')
    expect(document.body.textContent).toContain('Succeeded')
    expect(detail.stage).toBe('DISCOVERY')
    expect(detail.tasks[0]?.status).toBe('SUCCEEDED')
  })

  it('marks unknown historical state while preserving and safely rendering its exact raw text', async () => {
    const raw = '<img src=x onerror="alert(1)">  LEGACY'
    const detail = { ...project, stage: raw }
    vi.mocked(presalesApi.get).mockResolvedValue(detail)
    await mount(`/presales/${project.id}`)
    expect(document.body.textContent).toContain(`Unknown status: ${raw}`)
    expect(document.querySelector('img[src="x"]')).toBeNull()
    expect(detail.stage).toBe(raw)
    expect(presalesApi.update).not.toHaveBeenCalled()
    expect(presalesApi.command).not.toHaveBeenCalled()
    changeLocale('zh-CN')
    await settle()
    expect(document.body.textContent).toContain(`未知状态：${raw}`)
  })

  it('tracks host locale for known states while keeping the original wire values', async () => {
    const detail = {
      ...project,
      stage: 'DISCOVERY',
      tasks: [{ id: 'status-task', status: 'SUCCEEDED', skill: 'S1' }],
    }
    vi.mocked(presalesApi.get).mockResolvedValue(detail)
    await mount(`/presales/${project.id}`)
    changeLocale('zh-CN')
    await settle()
    expect(document.body.textContent).toContain('项目理解')
    expect(document.body.textContent).toContain('已完成')
    expect(detail.tasks[0]?.status).toBe('SUCCEEDED')
  })
})

describe('execution session page characterization', () => {
  const task = { id: '90071992547409990', status: 'RUNNING', skill: 'S1' }
  const assigned = { ...project, agentId: '90071992547409996', tasks: [task] }

  it.each([
    ['generate', 'resolve'],
    ['generate', 'reject'],
    ['cancel', 'resolve'],
    ['cancel', 'reject'],
  ] as const)(
    'ignores old %s %s after workspace ABA without unlocking the new request',
    async (action, outcome) => {
      vi.mocked(presalesApi.get).mockResolvedValue(assigned)
      let finishOld!: () => void
      let finishNew!: () => void
      const api = action === 'generate' ? presalesApi.generate : presalesApi.cancelTask
      vi.mocked(api)
        .mockImplementationOnce(
          () =>
            new Promise((resolve, reject) => {
              finishOld = () =>
                outcome === 'resolve'
                  ? resolve({ ...assigned, version: 9, name: 'Stale execution project' })
                  : reject({ response: { status: 409, data: { msg: 'Stale execution conflict' } } })
            }),
        )
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finishNew = () =>
                resolve({ ...assigned, version: 4, name: 'Current execution project' })
            }),
        )
      await mount(`/presales/${project.id}`)
      async function launch() {
        if (action === 'generate') {
          button('Delegate to employee').click()
          await settle()
          button('Start work').click()
        } else button('Discard this run result').click()
        await settle()
      }
      await launch()
      expect(api).toHaveBeenCalledTimes(1)
      workspaceFixture.current.currentWorkspaceId = 'another-workspace'
      await settle()
      workspaceFixture.current.currentWorkspaceId = project.workspaceId
      await settle()
      await launch()
      expect(api).toHaveBeenCalledTimes(2)
      finishOld()
      await settle()
      expect(document.body.textContent).not.toContain('Stale execution project')
      expect(document.body.textContent).not.toContain('Stale execution conflict')
      button(action === 'generate' ? 'Start work' : 'Discard this run result').click()
      await settle()
      expect(api).toHaveBeenCalledTimes(2)
      finishNew()
      await settle()
      expect(document.body.textContent).toContain('Current execution project')
    },
  )

  it('keeps generation input and receipt after 409 and changes the receipt for a different goal', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue(assigned)
    vi.mocked(presalesApi.generate).mockRejectedValue({
      response: { status: 409, data: { msg: 'Version moved' } },
    })
    await mount(`/presales/${project.id}`)
    button('Delegate to employee').click()
    await settle()
    const goal = document.querySelector<HTMLTextAreaElement>('.el-dialog textarea')!
    goal.value = 'Exact employee task'
    goal.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    button('Start work').click()
    await settle()
    expect(document.body.textContent).toContain('Version moved')
    expect(goal.value).toBe('Exact employee task')
    button('Start work').click()
    await settle()
    const first = vi.mocked(presalesApi.generate).mock.calls[0][2]
    expect(vi.mocked(presalesApi.generate).mock.calls[1][2]).toEqual(first)
    goal.value = 'Different employee task'
    goal.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    button('Start work').click()
    await settle()
    expect(vi.mocked(presalesApi.generate).mock.calls[2][2]).toEqual({
      ...first,
      taskGoal: 'Different employee task',
      operationId: expect.any(String),
    })
    expect(vi.mocked(presalesApi.generate).mock.calls[2][2].operationId).not.toBe(first.operationId)
  })

  it('keeps cancellation retry receipt and exposes its 409 without replacing the project', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue(assigned)
    vi.mocked(presalesApi.cancelTask).mockRejectedValue({
      response: { status: 409, data: { msg: 'Cancellation conflict' } },
    })
    await mount(`/presales/${project.id}`)
    button('Discard this run result').click()
    await settle()
    expect(document.body.textContent).toContain('Cancellation conflict')
    expect(document.body.textContent).toContain(project.name)
    button('Discard this run result').click()
    await settle()
    expect(presalesApi.cancelTask).toHaveBeenCalledTimes(2)
    expect(vi.mocked(presalesApi.cancelTask).mock.calls[1]).toEqual(
      vi.mocked(presalesApi.cancelTask).mock.calls[0],
    )
  })
})

describe('discovery panel boundaries', () => {
  async function selectPanel(name: string) {
    document.querySelector<HTMLElement>(`#tab-${name}`)!.click()
    await settle()
    return document.querySelector<HTMLElement>(`#pane-${name}`)!
  }

  it('keeps unknown clarifications separate and retains the selected filter across project navigation', async () => {
    const questions = [
      { id: 'open', question: 'Open question', status: 'OPEN', ownerId: project.ownerId },
      { id: 'unknown', question: 'Legacy question', status: 'LEGACY_PENDING' },
      {
        id: 'answered',
        question: 'Answered question',
        status: 'ANSWERED',
        answer: 'Recorded answer',
      },
    ]
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, clarifications: questions })
    const router = await mount(`/presales/${project.id}`)
    const pane = await selectPanel('requirements')
    const open = [...pane.querySelectorAll<HTMLElement>('.el-radio-button')].find((item) =>
      item.textContent?.trim().startsWith('Open'),
    )!
    expect(open.textContent?.trim()).toBe('Open 1')
    open.querySelector<HTMLInputElement>('input')!.click()
    await settle()
    expect(pane.textContent).toContain('Open question')
    expect(pane.textContent).not.toContain('Legacy question')
    expect(pane.textContent).not.toContain('Answered question')
    expect(pane.textContent).toContain('Jane Doe')
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      id: 'second-project',
      clarifications: questions,
    })
    await router.push('/presales/second-project')
    await settle()
    const replacement = await selectPanel('requirements')
    expect(replacement.textContent).not.toContain('Legacy question')
    expect(replacement.textContent).not.toContain('Answered question')
    expect(replacement.querySelector<HTMLInputElement>('input[value="OPEN"]')?.checked).toBe(true)
    replacement.querySelector<HTMLInputElement>('input[value="ALL"]')!.click()
    await settle()
    expect(replacement.textContent).toContain('Legacy question')
    expect(replacement.textContent).toContain('Unknown status: LEGACY_PENDING')
    expect(questions[1].status).toBe('LEGACY_PENDING')
  })

  it('keeps withdrawn materials disabled and unbinds the exact selected string ID', async () => {
    const current = {
      ...project,
      materials: [
        {
          id: '9223372036854775801',
          kbId: 'active-kb',
          graphId: 'graph',
          role: 'PROJECT',
          status: 'BOUND',
        },
        {
          id: 'withdrawn',
          kbId: 'withdrawn-kb',
          graphId: 'old',
          role: 'PROJECT',
          status: 'WITHDRAWN',
        },
      ],
    }
    vi.mocked(presalesApi.get).mockResolvedValue(current)
    vi.mocked(presalesApi.command).mockResolvedValue({ ...current, version: 4 })
    await mount(`/presales/${project.id}`)
    const pane = await selectPanel('materials')
    const withdraw = [...pane.querySelectorAll<HTMLButtonElement>('button')].filter(
      (item) => item.textContent?.trim() === 'Withdraw',
    )
    expect(withdraw).toHaveLength(2)
    expect(withdraw[1].disabled).toBe(true)
    withdraw[0].click()
    await settle()
    expect(presalesApi.command).toHaveBeenCalledWith(project.workspaceId, project.id, {
      action: 'UNBIND_MATERIAL',
      payload: { id: '9223372036854775801' },
      expectedVersion: 3,
      operationId: expect.any(String),
    })
  })

  it('renders capability evidence as safe text and routes the selected record to the evidence drawer', async () => {
    const fit = {
      id: 'fit-9223372036854775801',
      requirementId: 'requirement',
      status: 'UNKNOWN',
      reason: '<img src=x onerror=alert(1)>',
      productVersion: 'v-exact',
    }
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, fitGaps: [fit] })
    await mount(`/presales/${project.id}`)
    const pane = await selectPanel('fitgap')
    expect(pane.textContent).toContain(fit.reason)
    expect(pane.textContent).toContain('v-exact')
    expect(pane.querySelector('img')).toBeNull()
    const evidence = [...pane.querySelectorAll<HTMLButtonElement>('button')].find(
      (item) => item.textContent?.trim() === 'Evidence',
    )!
    evidence.click()
    await settle()
    expect(document.querySelector('.el-drawer')?.textContent).toContain(fit.id)
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it('keeps all discovery mutation intents disabled for a read-only viewer', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({
      enabled: true,
      semanticEnabled: true,
      canWrite: false,
      canApprove: false,
    })
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      agentId: 'employee',
      requirements: [{ id: 'req', title: 'Requirement' }],
      clarifications: [{ id: 'q', question: 'Question', status: 'OPEN' }],
    })
    await mount(`/presales/${project.id}`)
    for (const name of ['materials', 'requirements', 'fitgap']) {
      const pane = await selectPanel(name)
      const actions = [...pane.querySelectorAll<HTMLButtonElement>('button')].filter(
        (item) =>
          !['Evidence', 'View record', 'View source'].includes(item.textContent?.trim() || ''),
      )
      expect(actions.length).toBeGreaterThan(0)
      for (const action of actions) expect(action.disabled).toBe(true)
    }
    expect(presalesApi.command).not.toHaveBeenCalled()
    expect(presalesApi.generate).not.toHaveBeenCalled()
  })
})

describe('remaining display contract', () => {
  it.each(['employees', 'generate', 'cancel'] as const)(
    'updates an existing %s API failure when the host language changes',
    async (action) => {
      vi.mocked(presalesApi.get).mockResolvedValue({
        ...project,
        agentId: '90071992547409996',
        tasks: [{ id: 'failed-request-task', status: 'RUNNING', skill: 'S1' }],
      })
      const api =
        action === 'employees'
          ? presalesApi.employees
          : action === 'generate'
            ? presalesApi.generate
            : presalesApi.cancelTask
      vi.mocked(api).mockRejectedValue({
        response: {
          status: 503,
          data: { msg: 'EMPLOYEE_RUNTIME_UNAVAILABLE' },
        },
      })
      await mount(`/presales/${project.id}`)
      if (action === 'cancel') button('Discard this run result').click()
      else {
        button('Delegate to employee').click()
        await settle()
        if (action === 'generate') button('Start work').click()
      }
      await settle()
      expect(api).toHaveBeenCalledTimes(1)
      const alerts = () =>
        [...document.querySelectorAll('.el-alert--error .el-alert__title')].map(
          (item) => item.textContent,
        )
      expect(alerts()).toContain('Employee runtime is unavailable.')
      changeLocale('zh-CN')
      await settle()
      expect(alerts()).toContain('员工运行服务暂不可用。')
      expect(alerts()).not.toContain('Employee runtime is unavailable.')
      changeLocale('en-US')
      await settle()
      expect(alerts()).toContain('Employee runtime is unavailable.')
      expect(alerts()).not.toContain('员工运行服务暂不可用。')
      expect(api).toHaveBeenCalledTimes(1)
    },
  )

  // Captured verbatim before moving the employee messages into the existing catalogue.
  it.each([
    [
      'EMPLOYEE_UNAVAILABLE',
      '负责员工不可用，请检查绑定、工作区与启用状态。',
      'Assigned employee unavailable. Check assignment, workspace and enabled state.',
    ],
    [
      'EMPLOYEE_RUNTIME_FAILED',
      '员工执行失败，请查看执行过程并检查员工的模型配置后重试。',
      'Employee execution failed. Check the execution and model configuration before retrying.',
    ],
    ['EMPLOYEE_RUNTIME_UNAVAILABLE', '员工运行服务暂不可用。', 'Employee runtime is unavailable.'],
    [
      'PRESENTATION_UNAVAILABLE',
      '成果编译服务暂不可用，本次执行未完成。',
      'Presentation compiler is unavailable; this run did not complete.',
    ],
    [
      'PRESENTATION_FAILED',
      '成果草稿编译失败，请检查页面内容后重试。',
      'Presentation draft compilation failed; review the content and retry.',
    ],
    [
      'PPT_GENERATION_FAILED',
      '成果草稿生成失败，请检查 PPT 技能配置后重试。',
      'Output draft generation failed; check the PPT skill configuration and retry.',
    ],
    [
      'PPT_GENERATION_TIMEOUT',
      '成果草稿生成超时，请稍后重试。',
      'Output draft generation timed out; retry later.',
    ],
    [
      'PROJECT_CHANGED_DURING_GENERATION',
      '执行期间项目已变化，本次结果未采纳。请重新执行。',
      'Project changed during execution. Results were not applied; run again.',
    ],
    ['LEGACY_EMPLOYEE_FAILURE', 'LEGACY_EMPLOYEE_FAILURE', 'LEGACY_EMPLOYEE_FAILURE'],
    ['constructor', 'constructor', 'constructor'],
    ['__proto__', '__proto__', '__proto__'],
    ['toString', 'toString', 'toString'],
  ])('preserves employee error %s through actual host language changes', async (code, zh, en) => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      tasks: [{ id: 'error-task', skill: 'S1', status: 'FAILED', error: code }],
    })
    await mount(`/presales/${project.id}`)
    expect(document.querySelector('#pane-overview')?.textContent).toContain(en)
    changeLocale('zh-CN')
    await settle()
    expect(document.querySelector('#pane-overview')?.textContent).toContain(zh)
    changeLocale('en-US')
    await settle()
    expect(document.querySelector('#pane-overview')?.textContent).toContain(en)
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it.each(['stage', 'status'] as const)(
    'rejects a foreign project %s in header and ledger',
    async (field) => {
      const detail: PresalesProject = { ...project, [field]: 'RUNNING' }
      vi.mocked(presalesApi.get).mockResolvedValue(detail)
      const router = await mount(`/presales/${project.id}`)
      expect(document.body.textContent).toContain('Unknown status: RUNNING')
      vi.mocked(presalesApi.list).mockResolvedValue({
        items: [detail],
        total: 1,
        page: 1,
        pageSize: 20,
      })
      await router.push('/presales')
      await settle()
      expect(document.querySelector('.project-ledger')?.textContent).toContain(
        'Unknown status: RUNNING',
      )
      expect(detail[field]).toBe('RUNNING')
    },
  )

  it.each([
    ['priority', 'RUNNING'],
    ['scope', 'ACTIVE'],
    ['originKind', 'ANSWERED'],
    ['customerConfirmationStatus', 'APPROVED'],
  ])('does not treat foreign requirement %s as known', async (field, raw) => {
    const item = {
      id: 'requirement-state',
      title: 'Preserved requirement',
      [field]: raw,
    }
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      requirements: [item],
    })
    await mount(`/presales/${project.id}`)
    document.querySelector<HTMLElement>('#tab-requirements')!.click()
    await settle()
    expect(document.querySelector('#pane-requirements')?.textContent).toContain(
      `Unknown status: ${raw}`,
    )
    changeLocale('zh-CN')
    await settle()
    expect(document.querySelector('#pane-requirements')?.textContent).toContain(`未知状态：${raw}`)
    expect(item[field]).toBe(raw)
  })

  it.each([
    ['role', 'RUNNING'],
    ['status', 'ACTIVE'],
  ])('does not borrow another object material %s', async (field, raw) => {
    const item = { id: 'material-state', kbId: 'kb', [field]: raw }
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      materials: [item],
    })
    await mount(`/presales/${project.id}`)
    document.querySelector<HTMLElement>('#tab-materials')!.click()
    await settle()
    expect(document.querySelector('#pane-materials')?.textContent).toContain(
      `Unknown status: ${raw}`,
    )
    expect(button('Withdraw').disabled).toBe(false)
    expect(item[field]).toBe(raw)
  })

  it('labels the already recognized withdrawn material without enabling withdrawal', async () => {
    vi.mocked(presalesApi.get).mockResolvedValue({
      ...project,
      materials: [{ id: 'withdrawn', status: 'WITHDRAWN' }],
    })
    await mount(`/presales/${project.id}`)
    document.querySelector<HTMLElement>('#tab-materials')!.click()
    await settle()
    expect(document.querySelector('#pane-materials')?.textContent).toContain('Withdrawn')
    expect(document.querySelector('#pane-materials')?.textContent).not.toContain(
      'Unknown status: WITHDRAWN',
    )
    expect(button('Withdraw').disabled).toBe(true)
    changeLocale('zh-CN')
    await settle()
    expect(document.querySelector('#pane-materials')?.textContent).toContain('已撤回')
    expect(presalesApi.command).not.toHaveBeenCalled()
  })

  it('keeps solution draft fallback but rejects a foreign explicit state', async () => {
    const solutions = [
      { id: 'implicit', title: 'Implicit draft' },
      { id: 'foreign', title: 'Foreign state', status: 'ANSWERED' },
    ]
    vi.mocked(presalesApi.get).mockResolvedValue({ ...project, solutions })
    await mount(`/presales/${project.id}`)
    document.querySelector<HTMLElement>('#tab-solution')!.click()
    await settle()
    const pane = document.querySelector('#pane-solution')!
    expect(pane.textContent).toContain('Unknown status: ANSWERED')
    expect(pane.textContent).toContain('Draft')
    expect(solutions[0]).not.toHaveProperty('status')
    expect(presalesApi.command).not.toHaveBeenCalled()
  })
})
