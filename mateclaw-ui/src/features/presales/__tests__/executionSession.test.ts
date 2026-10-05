import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import {
  presalesApi,
  type PresalesEmployee,
  type PresalesProject,
  type PresalesTask,
} from '../api/presalesApi'
import { usePresalesExecutionSession } from '../composables/usePresalesExecutionSession'
import { operationReceipt } from '../shared/state'

vi.mock('../api/presalesApi', () => ({
  presalesApi: { employees: vi.fn(), generate: vi.fn(), cancelTask: vi.fn() },
}))
const detail: PresalesProject = {
  id: '90071992547409998',
  workspaceId: '90071992547409999',
  version: 3,
  name: 'Plant A',
  customer: 'Customer',
  ownerId: '90071992547409997',
  agentId: '90071992547409996',
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
const employee: PresalesEmployee = {
  id: '90071992547409996',
  name: 'Presales specialist',
  enabled: true,
  available: true,
}
const task: PresalesTask = { id: '90071992547409995', status: 'RUNNING' }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
let scope: EffectScope
function setup() {
  const current = ref<PresalesProject | undefined>(structuredClone(detail))
  const workspaceId = ref<string | null>(detail.workspaceId),
    projectId = ref(detail.id),
    scopeGeneration = ref(0),
    writable = ref(true),
    dirty = ref(false),
    saving = ref(false),
    error = ref(''),
    conflict = ref(false),
    accept = ref(true)
  const captureScope = () => ({
    ws: workspaceId.value,
    id: projectId.value,
    generation: scopeGeneration.value,
  })
  const isActiveScope = (value: ReturnType<typeof captureScope>) =>
    value.ws === workspaceId.value &&
    value.id === projectId.value &&
    value.generation === scopeGeneration.value
  const acceptMutation = vi.fn((result: PresalesProject) => {
    if (!accept.value) return false
    current.value = result
    return true
  })
  const startPolling = vi.fn()
  scope = effectScope()
  const session = scope.run(() =>
    usePresalesExecutionSession({
      project: current,
      saving,
      error,
      conflict,
      workspaceId: () => workspaceId.value,
      projectId: () => projectId.value,
      canWrite: () => writable.value,
      canGenerate: () => writable.value && !current.value?.sourceAccessRestricted,
      isDirty: () => dirty.value,
      captureScope,
      isActiveScope,
      receipt: operationReceipt(),
      acceptMutation,
      startPolling,
      employeeIssue: (message) => `employee:${message}`,
      t: (key) => `translated:${key}`,
    }),
  )!
  return {
    ...session,
    current,
    workspaceId,
    projectId,
    scopeGeneration,
    writable,
    dirty,
    saving,
    error,
    conflict,
    accept,
    acceptMutation,
    startPolling,
  }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(presalesApi.employees).mockResolvedValue([employee])
  vi.mocked(presalesApi.generate).mockResolvedValue({ ...detail, version: 4 })
  vi.mocked(presalesApi.cancelTask).mockResolvedValue({ ...detail, version: 4 })
})
afterEach(() => scope?.stop())

describe('presales execution session contracts', () => {
  it('loads employee choices for a generation opening and retains localized continuation defaults', async () => {
    const session = setup()
    await session.openGeneration('S1')
    expect(session.generationOpen.value).toBe(true)
    expect(session.generation.value).toEqual({
      skill: 'S1',
      taskGoal: 'translated:presales.context_message_30',
    })
    expect(presalesApi.employees).toHaveBeenCalledWith(detail.workspaceId)
    expect(session.employees.value).toEqual([employee])
    await session.continueEmployee()
    expect(session.generation.value).toEqual({
      skill: 'S2',
      taskGoal: 'translated:presales.context_message_31',
    })
  })

  it.each(['dirty', 'saving', 'readonly', 'restricted'] as const)(
    'blocks generation opening and submission when %s',
    async (guard) => {
      const session = setup()
      if (guard === 'dirty') session.dirty.value = true
      if (guard === 'saving') session.saving.value = true
      if (guard === 'readonly') session.writable.value = false
      if (guard === 'restricted') session.current.value!.sourceAccessRestricted = true
      await session.continueEmployee()
      await session.generate()
      expect(session.generationOpen.value).toBe(false)
      expect(presalesApi.employees).not.toHaveBeenCalled()
      expect(presalesApi.generate).not.toHaveBeenCalled()
    },
  )

  it('shares its mutation lock between generation and cancellation and accepts one success', async () => {
    const pending = deferred<PresalesProject>()
    vi.mocked(presalesApi.generate).mockReturnValueOnce(pending.promise)
    const session = setup()
    await session.openGeneration('S4', '  Exact task goal  ')
    const request = session.generate()
    expect(session.saving.value).toBe(true)
    await session.cancelTask(task)
    await session.generate()
    expect(presalesApi.cancelTask).not.toHaveBeenCalled()
    expect(presalesApi.generate).toHaveBeenCalledTimes(1)
    expect(presalesApi.generate).toHaveBeenCalledWith(detail.workspaceId, detail.id, {
      skill: 'S4',
      taskGoal: '  Exact task goal  ',
      expectedVersion: 3,
      operationId: expect.any(String),
    })
    expect(vi.mocked(presalesApi.generate).mock.calls[0][2]).not.toHaveProperty('modelId')
    pending.resolve({ ...detail, version: 4 })
    await request
    expect(session.current.value?.version).toBe(4)
    expect(session.startPolling).toHaveBeenCalledOnce()
    expect(session.generationOpen.value).toBe(false)
    expect(session.saving.value).toBe(false)
  })

  it('keeps a rejected mutation visible for page version conflict handling', async () => {
    const session = setup()
    session.accept.value = false
    await session.openGeneration('S3', 'Preserve input')
    await session.generate()
    expect(session.acceptMutation).toHaveBeenCalledOnce()
    expect(session.current.value?.version).toBe(3)
    expect(session.generationOpen.value).toBe(true)
    expect(session.generation.value.taskGoal).toBe('Preserve input')
    expect(session.startPolling).not.toHaveBeenCalled()
    expect(session.saving.value).toBe(false)
  })

  it.each(['success', 'failure'] as const)(
    'ignores an earlier employee %s in the same scope without clearing the new request',
    async (outcome) => {
      const old = deferred<PresalesEmployee[]>(),
        replacement = deferred<PresalesEmployee[]>()
      vi.mocked(presalesApi.employees)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(replacement.promise)
      const session = setup()
      const first = session.loadEmployees(),
        second = session.loadEmployees()
      if (outcome === 'success') old.resolve([employee])
      else old.reject(new Error('Old list error'))
      await first
      expect(session.employees.value).toEqual([])
      expect(session.employeeError.value).toBe('')
      expect(session.employeesLoading.value).toBe(true)
      replacement.resolve([{ ...employee, name: 'Current employee' }])
      await second
      expect(session.employees.value[0].name).toBe('Current employee')
      expect(session.employeesLoading.value).toBe(false)
    },
  )

  it('maps the current employee error and resets it before reloading', async () => {
    vi.mocked(presalesApi.employees).mockRejectedValueOnce(new Error('EMPLOYEE_UNAVAILABLE'))
    const session = setup()
    await session.loadEmployees()
    expect(session.employeeError.value).toBe('employee:EMPLOYEE_UNAVAILABLE')
    expect(session.employeesLoading.value).toBe(false)
    await session.loadEmployees()
    expect(session.employeeError.value).toBe('')
    expect(session.employees.value).toEqual([employee])
  })

  it.each(['generate', 'cancel'] as const)(
    'maps %s conflict and reuses an uncertain receipt',
    async (action) => {
      const api = action === 'generate' ? presalesApi.generate : presalesApi.cancelTask
      vi.mocked(api).mockRejectedValue({
        response: { status: 409, data: { msg: 'OPERATION_CONFLICT' } },
      })
      const session = setup()
      await session.openGeneration('S5', 'Same goal')
      const run = () => (action === 'generate' ? session.generate() : session.cancelTask(task))
      await run()
      await run()
      expect(session.error.value).toBe('employee:OPERATION_CONFLICT')
      expect(session.employeeError.value).toBe(session.error.value)
      expect(session.conflict.value).toBe(true)
      expect(session.saving.value).toBe(false)
      expect(vi.mocked(api).mock.calls[1]).toEqual(vi.mocked(api).mock.calls[0])
      if (action === 'generate') {
        session.current.value!.version = 4
        await run()
        const calls = vi.mocked(presalesApi.generate).mock.calls
        expect(calls[2][2].operationId).not.toBe(calls[0][2].operationId)
        expect(calls[2][2].expectedVersion).toBe(4)
      } else {
        await session.cancelTask({ ...task, id: '90071992547409994' })
        const calls = vi.mocked(presalesApi.cancelTask).mock.calls
        expect(calls[2][3].operationId).not.toBe(calls[0][3].operationId)
        expect(calls[2][2]).toBe('90071992547409994')
      }
    },
  )

  it.each(['saving', 'readonly', 'finished', 'workspace', 'project'] as const)(
    'blocks cancellation for %s without issuing another mutation',
    async (guard) => {
      const session = setup()
      if (guard === 'saving') session.saving.value = true
      if (guard === 'readonly') session.writable.value = false
      if (guard === 'workspace') session.workspaceId.value = null
      if (guard === 'project') session.projectId.value = ''
      await session.cancelTask(guard === 'finished' ? { ...task, status: 'SUCCEEDED' } : task)
      expect(presalesApi.cancelTask).not.toHaveBeenCalled()
    },
  )

  it('keeps cancellation available to stop an existing run during source restriction', async () => {
    const session = setup()
    session.current.value!.sourceAccessRestricted = true
    await session.cancelTask(task)
    expect(presalesApi.cancelTask).toHaveBeenCalledWith(detail.workspaceId, detail.id, task.id, {
      operationId: expect.any(String),
    })
    expect(session.acceptMutation).toHaveBeenCalledOnce()
    expect(session.startPolling).not.toHaveBeenCalled()
  })

  it.each([
    ['generate', 'success'],
    ['generate', 'failure'],
    ['cancel', 'success'],
    ['cancel', 'failure'],
    ['employees', 'success'],
    ['employees', 'failure'],
  ] as const)(
    'does not accept %s %s after disposal or issue another request',
    async (action, outcome) => {
      const mutation = deferred<PresalesProject>(),
        listing = deferred<PresalesEmployee[]>()
      vi.mocked(presalesApi.generate).mockReturnValueOnce(mutation.promise)
      vi.mocked(presalesApi.cancelTask).mockReturnValueOnce(mutation.promise)
      vi.mocked(presalesApi.employees).mockReturnValueOnce(listing.promise)
      const session = setup()
      const run = () =>
        action === 'generate'
          ? session.generate()
          : action === 'cancel'
            ? session.cancelTask(task)
            : session.loadEmployees()
      const request = run()
      scope.stop()
      if (action === 'employees') {
        if (outcome === 'success') listing.resolve([employee])
        else listing.reject(new Error('Late list failure'))
      } else if (outcome === 'success') mutation.resolve({ ...detail, version: 8 })
      else mutation.reject(new Error('Late mutation failure'))
      await request
      expect(session.acceptMutation).not.toHaveBeenCalled()
      expect(session.startPolling).not.toHaveBeenCalled()
      expect(session.error.value).toBe('')
      expect(session.employeeError.value).toBe('')
      expect(session.employees.value).toEqual([])
      if (action !== 'employees') expect(session.saving.value).toBe(true)
      await run()
      await session.openGeneration('S1')
      const api =
        action === 'generate'
          ? presalesApi.generate
          : action === 'cancel'
            ? presalesApi.cancelTask
            : presalesApi.employees
      expect(api).toHaveBeenCalledTimes(1)
      expect(session.generationOpen.value).toBe(false)
    },
  )

  it.each(['success', 'failure'] as const)(
    'ignores employee %s after scope ABA and keeps the new workspace list loading',
    async (outcome) => {
      const old = deferred<PresalesEmployee[]>(),
        replacement = deferred<PresalesEmployee[]>()
      vi.mocked(presalesApi.employees)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(replacement.promise)
      const session = setup()
      const first = session.loadEmployees()
      session.workspaceId.value = 'second-workspace'
      session.scopeGeneration.value++
      session.workspaceId.value = detail.workspaceId
      session.scopeGeneration.value++
      const second = session.loadEmployees()
      if (outcome === 'success') old.resolve([employee])
      else old.reject(new Error('Stale employee failure'))
      await first
      expect(session.employeeError.value).toBe('')
      expect(session.employees.value).toEqual([])
      expect(session.employeesLoading.value).toBe(true)
      replacement.resolve([employee])
      await second
      expect(session.employeesLoading.value).toBe(false)
    },
  )
})
