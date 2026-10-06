import { onScopeDispose, ref } from 'vue'
import {
  presalesApi,
  type PresalesCapabilities,
  type PresalesMember,
  type PresalesProject,
  type PresalesProjectSummary,
} from '../api/presalesApi'
import { loadPortfolio } from '../shared/dashboard'
import { isCurrentRequest, presalesError } from '../shared/state'

interface WorkbenchQueryOptions {
  workspaceId: () => string | null
  projectId: () => string
  isDirty: () => boolean
  t: (key: string) => string
  invalidateScope: () => void
  resetPolling: () => void
  resetView: () => void
  acceptProject: (detail: PresalesProject) => void
}

/** Owns read state and cancellation; page mutations and editing retain their own lifecycle. */
export function usePresalesWorkbenchQuery(options: WorkbenchQueryOptions) {
  const { t } = options
  let disposed = false
  let controller: AbortController | undefined
  const members = ref<PresalesMember[]>([]),
    membersLoading = ref(false),
    membersError = ref('')
  async function loadMembers(ws: string, signal: AbortSignal) {
    members.value = []
    membersError.value = ''
    membersLoading.value = true
    try {
      const rows = await presalesApi.members(ws, signal)
      if (!signal.aborted && ws === options.workspaceId()) members.value = rows
    } catch {
      if (!signal.aborted && ws === options.workspaceId())
        membersError.value = t('presales.context_message_27')
    } finally {
      if (!signal.aborted && ws === options.workspaceId()) membersLoading.value = false
    }
  }

  const capabilities = ref<PresalesCapabilities>(),
    project = ref<PresalesProject>(),
    projects = ref<PresalesProjectSummary[]>([])
  const portfolio = ref<PresalesProjectSummary[]>([]),
    portfolioLoading = ref(false),
    portfolioError = ref(false)
  let portfolioController: AbortController | undefined
  async function refreshPortfolio() {
    if (disposed) return
    portfolioController?.abort()
    portfolioController = new AbortController()
    const ws = options.workspaceId(),
      signal = portfolioController.signal
    portfolio.value = []
    portfolioError.value = false
    if (!ws || options.projectId() || !capabilities.value?.enabled) {
      portfolioLoading.value = false
      return
    }
    portfolioLoading.value = true
    try {
      const rows = await loadPortfolio(
        (page) => presalesApi.list(ws, { page, pageSize: 100 }, signal),
        signal,
      )
      if (!signal.aborted && ws === options.workspaceId()) portfolio.value = rows
    } catch {
      if (!signal.aborted) portfolioError.value = true
    } finally {
      if (!signal.aborted) portfolioLoading.value = false
    }
  }
  const loading = ref(false),
    error = ref(''),
    conflict = ref(false),
    page = ref(1),
    total = ref(0)
  const query = ref(''),
    ownerFilter = ref(''),
    statusFilter = ref('')
  async function readProject(
    ws: string,
    id: string,
    signal: AbortSignal,
  ): Promise<PresalesProject> {
    try {
      return await presalesApi.get(ws, id, signal)
    } catch (e) {
      if (
        disposed ||
        !presalesError(e).accessDenied ||
        signal.aborted ||
        !isCurrentRequest(ws, options.workspaceId(), id, options.projectId())
      )
        throw e
      project.value = undefined
      if (!capabilities.value?.canWrite) throw e
      try {
        const repair = await presalesApi.repairContext(ws, id, signal)
        const sourceCollections = [
          'materials',
          'requirements',
          'clarifications',
          'baselines',
          'fitGaps',
          'cases',
          'solutions',
          'reviews',
          'reviewDrafts',
          'releases',
          'tasks',
          'contextCards',
        ] as const
        const metadata = [
          'id',
          'workspaceId',
          'version',
          'name',
          'customer',
          'ownerId',
          'industry',
          'goal',
          'status',
          'stage',
          'agentId',
          'agentName',
          'createdBy',
          'createdAt',
          'updatedBy',
          'updatedAt',
          'sourceAccessRestricted',
          'repairBindings',
        ]
        const allowedKeys = new Set<string>([...metadata, ...sourceCollections])
        if (
          repair.sourceAccessRestricted !== true ||
          repair.id !== id ||
          repair.workspaceId !== ws ||
          !Number.isInteger(repair.version) ||
          Object.keys(repair).some((key) => !allowedKeys.has(key)) ||
          sourceCollections.some(
            (key) => !Array.isArray(repair[key]) || repair[key].length !== 0,
          ) ||
          !Array.isArray(repair.repairBindings) ||
          repair.repairBindings.some(
            (binding) =>
              typeof binding.id !== 'string' ||
              !binding.id ||
              !['PROJECT', 'PRODUCT', 'CASE', 'UNKNOWN'].includes(binding.role) ||
              Object.keys(binding).some((key) => !['id', 'role'].includes(key)),
          )
        )
          throw e
        return { ...repair }
      } catch {
        throw e
      }
    }
  }
  async function load() {
    if (disposed || options.isDirty()) return
    options.invalidateScope()
    controller?.abort()
    controller = new AbortController()
    options.resetPolling()
    const ws = options.workspaceId(),
      id = options.projectId(),
      signal = controller.signal
    members.value = []
    membersError.value = ''
    membersLoading.value = false
    portfolioController?.abort()
    portfolio.value = []
    portfolioError.value = false
    portfolioLoading.value = false
    project.value = undefined
    projects.value = []
    capabilities.value = undefined
    options.resetView()
    loading.value = false
    if (!ws) {
      error.value = t('presales.select_a_workspace')
      return
    }
    loading.value = true
    error.value = ''
    conflict.value = false
    try {
      const caps = await presalesApi.capabilities(ws, signal)
      if (!isCurrentRequest(ws, options.workspaceId(), id, options.projectId()) || signal.aborted)
        return
      capabilities.value = caps
      if (!caps.enabled) return
      void loadMembers(ws, signal)
      if (!id) void refreshPortfolio()
      if (id) {
        const detail = await readProject(ws, id, signal)
        if (
          isCurrentRequest(ws, options.workspaceId(), id, options.projectId()) &&
          !signal.aborted
        ) {
          options.acceptProject(detail)
        }
      } else {
        const result = await presalesApi.list(
          ws,
          {
            q: query.value,
            ownerId: ownerFilter.value,
            stage: statusFilter.value,
            page: page.value,
            pageSize: 20,
          },
          signal,
        )
        if (
          isCurrentRequest(ws, options.workspaceId(), id, options.projectId()) &&
          !signal.aborted
        ) {
          projects.value = result.items
          total.value = Number(result.total)
        }
      }
    } catch (e) {
      if (!signal.aborted) {
        if (
          (e as { response?: { status?: number } }).response?.status === 404 &&
          !capabilities.value
        )
          capabilities.value = {
            enabled: false,
            semanticEnabled: false,
            canWrite: false,
            canApprove: false,
          }
        else error.value = presalesError(e).message
      }
    } finally {
      if (!signal.aborted) loading.value = false
    }
  }
  function search() {
    page.value = 1
    void load()
  }
  function dispose() {
    disposed = true
    controller?.abort()
    portfolioController?.abort()
    loading.value = false
    membersLoading.value = false
    portfolioLoading.value = false
  }
  onScopeDispose(dispose)
  return {
    members,
    membersLoading,
    membersError,
    capabilities,
    project,
    projects,
    portfolio,
    portfolioLoading,
    portfolioError,
    loading,
    error,
    conflict,
    page,
    total,
    query,
    ownerFilter,
    statusFilter,
    load,
    readProject,
    refreshPortfolio,
    search,
    dispose,
  }
}
