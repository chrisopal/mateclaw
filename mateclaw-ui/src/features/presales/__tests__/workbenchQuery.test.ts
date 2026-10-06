import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import {
  presalesApi,
  type PresalesProject,
  type PresalesRepairContext,
  type ProjectPage,
} from '../api/presalesApi'
import { usePresalesWorkbenchQuery } from '../composables/usePresalesWorkbenchQuery'

vi.mock('../api/presalesApi', () => ({
  presalesApi: {
    capabilities: vi.fn(),
    members: vi.fn(),
    get: vi.fn(),
    repairContext: vi.fn(),
    list: vi.fn(),
  },
}))
const caps = { enabled: true, semanticEnabled: true, canWrite: true, canApprove: true }
const detail: PresalesProject = {
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
const repair: PresalesRepairContext = {
  id: detail.id,
  workspaceId: detail.workspaceId,
  version: detail.version,
  name: detail.name,
  customer: detail.customer,
  ownerId: detail.ownerId,
  status: detail.status,
  materials: [],
  requirements: [],
  clarifications: [],
  baselines: [],
  fitGaps: [],
  solutions: [],
  reviews: [],
  releases: [],
  tasks: [],
  cases: [],
  contextCards: [],
  reviewDrafts: [],
  sourceAccessRestricted: true,
  repairBindings: [{ id: 'opaque-binding', role: 'PROJECT' }],
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const scopes: EffectScope[] = []
function setup(id = detail.id) {
  const scope = effectScope()
  scopes.push(scope)
  const workspaceId = ref<string | null>(detail.workspaceId),
    projectId = ref(id),
    dirty = ref(false)
  const invalidateScope = vi.fn(),
    resetPolling = vi.fn(),
    resetView = vi.fn()
  const query = scope.run(() =>
    usePresalesWorkbenchQuery({
      workspaceId: () => workspaceId.value,
      projectId: () => projectId.value,
      isDirty: () => dirty.value,
      t: (key) => key,
      invalidateScope,
      resetPolling,
      resetView,
      acceptProject: (value) => {
        query.project.value = value
      },
    }),
  )!
  return { query, workspaceId, projectId, dirty, scope, invalidateScope, resetPolling, resetView }
}
async function settle() {
  for (let i = 0; i < 12; i++) await Promise.resolve()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(presalesApi.capabilities).mockResolvedValue(caps)
  vi.mocked(presalesApi.members).mockResolvedValue([])
  vi.mocked(presalesApi.get).mockResolvedValue(detail)
  vi.mocked(presalesApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
})
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
})

describe('workbench query lifecycle', () => {
  it.each(['resolve', 'reject'] as const)(
    'ignores an old detail %s after an A-B-A load',
    async (completion) => {
      const old = deferred<PresalesProject>()
      vi.mocked(presalesApi.get).mockReturnValueOnce(old.promise)
      const { query, workspaceId } = setup()
      const first = query.load()
      await settle()
      const oldSignal = vi.mocked(presalesApi.get).mock.calls[0][2]!
      workspaceId.value = 'other-workspace'
      await query.load()
      workspaceId.value = detail.workspaceId
      vi.mocked(presalesApi.get).mockResolvedValue({ ...detail, name: 'Latest', version: 7 })
      await query.load()
      expect(oldSignal.aborted).toBe(true)
      if (completion === 'resolve') old.resolve({ ...detail, name: 'Stale' })
      else old.reject({ response: { status: 403 } })
      await first
      expect(query.project.value?.name).toBe('Latest')
      expect(query.error.value).toBe('')
      expect(presalesApi.repairContext).not.toHaveBeenCalled()
    },
  )

  it('keeps a dirty editor and its in-flight request intact', async () => {
    const pending = deferred<typeof caps>()
    vi.mocked(presalesApi.capabilities).mockReturnValueOnce(pending.promise)
    const { query, dirty, invalidateScope, resetPolling, resetView } = setup()
    const first = query.load()
    const signal = vi.mocked(presalesApi.capabilities).mock.calls[0][1]!
    dirty.value = true
    await query.load()
    expect(signal.aborted).toBe(false)
    expect(invalidateScope).toHaveBeenCalledTimes(1)
    expect(resetPolling).toHaveBeenCalledTimes(1)
    expect(resetView).toHaveBeenCalledTimes(1)
    pending.resolve(caps)
    await first
    expect(query.project.value).toEqual(detail)
  })

  it.each(['disabled', 'missing', '404'] as const)(
    'clears previous query data for %s workspace capabilities',
    async (state) => {
      const { query, workspaceId } = setup()
      await query.load()
      expect(query.project.value).toEqual(detail)
      vi.mocked(presalesApi.get).mockClear()
      vi.mocked(presalesApi.members).mockClear()
      if (state === 'missing') workspaceId.value = null
      else if (state === '404')
        vi.mocked(presalesApi.capabilities).mockRejectedValue({ response: { status: 404 } })
      else vi.mocked(presalesApi.capabilities).mockResolvedValue({ ...caps, enabled: false })
      await query.load()
      expect(query.loading.value).toBe(false)
      expect(query.project.value).toBeUndefined()
      expect(query.projects.value).toEqual([])
      expect(query.members.value).toEqual([])
      expect(query.portfolio.value).toEqual([])
      expect(presalesApi.get).not.toHaveBeenCalled()
      expect(presalesApi.members).not.toHaveBeenCalled()
      if (state === 'missing') expect(query.error.value).toBe('presales.select_a_workspace')
      else expect(query.capabilities.value?.enabled).toBe(false)
    },
  )

  it('pins filters to the ledger query while independently paging the portfolio', async () => {
    const rows = Array.from({ length: 200 }, (_, i) => ({ ...detail, id: String(9000 + i) }))
    vi.mocked(presalesApi.list).mockImplementation(async (_ws, params) => ({
      items:
        params.pageSize === 100
          ? rows.slice((params.page! - 1) * 100, params.page! * 100)
          : [detail],
      total: params.pageSize === 100 ? 200 : 1,
      page: params.page!,
      pageSize: params.pageSize!,
    }))
    const { query } = setup('')
    query.query.value = '  Exact query  '
    query.ownerFilter.value = detail.ownerId
    query.statusFilter.value = 'DISCOVERY'
    query.page.value = 3
    await query.load()
    await settle()
    expect(presalesApi.list).toHaveBeenCalledWith(
      detail.workspaceId,
      {
        q: '  Exact query  ',
        ownerId: detail.ownerId,
        stage: 'DISCOVERY',
        page: 3,
        pageSize: 20,
      },
      expect.any(AbortSignal),
    )
    expect(query.projects.value).toEqual([detail])
    expect(query.total.value).toBe(1)
    expect(query.portfolio.value).toEqual(rows)
  })

  it.each(['resolve', 'reject'] as const)(
    'does not apply old members or portfolio %s over a new load',
    async (completion) => {
      const members = deferred<Awaited<ReturnType<typeof presalesApi.members>>>()
      const portfolio = deferred<ProjectPage>()
      vi.mocked(presalesApi.members).mockReturnValueOnce(members.promise)
      vi.mocked(presalesApi.list).mockImplementationOnce(() => portfolio.promise)
      const { query } = setup('')
      await query.load()
      const memberSignal = vi.mocked(presalesApi.members).mock.calls[0][1]!
      const portfolioSignal = vi.mocked(presalesApi.list).mock.calls[0][2]!
      vi.mocked(presalesApi.members).mockResolvedValue([
        { id: 'member', workspaceId: detail.workspaceId, userId: detail.ownerId, role: 'member' },
      ])
      await query.load()
      await settle()
      expect(memberSignal.aborted).toBe(true)
      expect(portfolioSignal.aborted).toBe(true)
      if (completion === 'resolve') {
        members.resolve([])
        portfolio.resolve({ items: [detail], total: 1, page: 1, pageSize: 100 })
      } else {
        members.reject(new Error('old'))
        portfolio.reject(new Error('old'))
      }
      await settle()
      expect(query.members.value[0]?.id).toBe('member')
      expect(query.membersError.value).toBe('')
      expect(query.portfolio.value).toEqual([])
      expect(query.portfolioError.value).toBe(false)
      expect(query.membersLoading.value).toBe(false)
      expect(query.portfolioLoading.value).toBe(false)
    },
  )

  it('keeps independent member and portfolio failures visible without discarding the ledger', async () => {
    vi.mocked(presalesApi.members).mockRejectedValue(new Error('members'))
    vi.mocked(presalesApi.list).mockImplementation(async (_ws, params) => {
      if (params.pageSize === 100) throw new Error('portfolio')
      return { items: [detail], total: 1, page: 1, pageSize: 20 }
    })
    const { query } = setup('')
    await query.load()
    await settle()
    expect(query.projects.value).toEqual([detail])
    expect(query.error.value).toBe('')
    expect(query.membersError.value).toBe('presales.context_message_27')
    expect(query.portfolioError.value).toBe(true)
    expect(query.membersLoading.value).toBe(false)
    expect(query.portfolioLoading.value).toBe(false)
  })

  it.each(['resolve', 'reject'] as const)(
    'aborts all owned requests on scope disposal and ignores late %s',
    async (completion) => {
      const members = deferred<Awaited<ReturnType<typeof presalesApi.members>>>()
      const ledger = deferred<ProjectPage>(),
        portfolio = deferred<ProjectPage>()
      vi.mocked(presalesApi.members).mockReturnValue(members.promise)
      vi.mocked(presalesApi.list).mockImplementation((_ws, params) =>
        params.pageSize === 100 ? portfolio.promise : ledger.promise,
      )
      const { query, scope } = setup('')
      const loading = query.load()
      await settle()
      const signals = [
        vi.mocked(presalesApi.members).mock.calls[0][1],
        ...vi.mocked(presalesApi.list).mock.calls.map((call) => call[2]),
      ]
      scope.stop()
      expect(signals.every((signal) => signal?.aborted)).toBe(true)
      if (completion === 'resolve') {
        members.resolve([
          { id: 'late', workspaceId: detail.workspaceId, userId: detail.ownerId, role: 'member' },
        ])
        ledger.resolve({ items: [detail], total: 1, page: 1, pageSize: 20 })
        portfolio.resolve({ items: [detail], total: 1, page: 1, pageSize: 100 })
      } else {
        members.reject(new Error('late'))
        ledger.reject(new Error('late'))
        portfolio.reject(new Error('late'))
      }
      await loading
      await settle()
      await query.load()
      await query.refreshPortfolio()
      expect(presalesApi.capabilities).toHaveBeenCalledTimes(1)
      expect(presalesApi.list).toHaveBeenCalledTimes(2)
      expect(query.projects.value).toEqual([])
      expect(query.members.value).toEqual([])
      expect(query.portfolio.value).toEqual([])
      expect(query.error.value).toBe('')
      expect(query.membersError.value).toBe('')
      expect(query.portfolioError.value).toBe(false)
      expect(
        query.loading.value || query.membersLoading.value || query.portfolioLoading.value,
      ).toBe(false)
    },
  )

  it.each([
    ['metadata', { context: { secret: 'hidden' } }],
    ['source collection', { materials: [{ id: 'secret' }] }],
    ['opaque binding', { repairBindings: [{ id: 'binding', role: 'PROJECT', kbId: 'secret' }] }],
    ['wrong workspace', { workspaceId: 'other' }],
    ['wrong project', { id: 'other' }],
    ['fractional version', { version: 3.5 }],
  ])(
    'rejects repair with malformed %s and preserves the original denial',
    async (_name, malformed) => {
      const denied = { response: { status: 403 } }
      const { query } = setup()
      await query.load()
      vi.mocked(presalesApi.get).mockRejectedValue(denied)
      // The transport decoder also validates; exercise this second boundary against malformed runtime data.
      vi.mocked(presalesApi.repairContext).mockResolvedValue({
        ...repair,
        ...malformed,
      } as PresalesRepairContext)
      await expect(
        query.readProject(detail.workspaceId, detail.id, new AbortController().signal),
      ).rejects.toBe(denied)
      expect(query.project.value).toBeUndefined()
    },
  )

  it('accepts only source-empty repair metadata and opaque binding references after clearing sensitive details', async () => {
    const { query } = setup()
    await query.load()
    vi.mocked(presalesApi.get).mockRejectedValue({ response: { status: 403 } })
    const pending = deferred<PresalesRepairContext>()
    vi.mocked(presalesApi.repairContext).mockReturnValue(pending.promise)
    const reading = query.readProject(detail.workspaceId, detail.id, new AbortController().signal)
    await settle()
    expect(query.project.value).toBeUndefined()
    pending.resolve(repair)
    await expect(reading).resolves.toEqual(repair)
  })

  it('does not request repair for a viewer', async () => {
    vi.mocked(presalesApi.capabilities).mockResolvedValue({ ...caps, canWrite: false })
    const denied = { response: { status: 403 } }
    vi.mocked(presalesApi.get).mockRejectedValue(denied)
    const { query } = setup()
    await query.load()
    expect(query.project.value).toBeUndefined()
    expect(presalesApi.repairContext).not.toHaveBeenCalled()
  })
})
