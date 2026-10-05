import { onScopeDispose, ref, type Ref } from 'vue'
import {
  presalesApi,
  type PresalesEmployee,
  type PresalesProject,
  type PresalesSkill,
  type PresalesTask,
} from '../api/presalesApi'
import { presalesError } from '../shared/state'

interface ExecutionSessionOptions<Scope> {
  project: Ref<PresalesProject | undefined>
  saving: Ref<boolean>
  error: Ref<string>
  conflict: Ref<boolean>
  workspaceId: () => string | null
  projectId: () => string
  canWrite: () => boolean
  canGenerate: () => boolean
  isDirty: () => boolean
  captureScope: () => Scope
  isActiveScope: (scope: Scope) => boolean
  receipt: (input: unknown) => string
  acceptMutation: (detail: PresalesProject, scope: Scope) => boolean
  startPolling: () => void
  employeeIssue: (message: string) => string
  t: (key: string) => string
}

/** Employee execution shares the page scope, mutation lock and receipt history. */
export function usePresalesExecutionSession<Scope>(options: ExecutionSessionOptions<Scope>) {
  const generationOpen = ref(false),
    employees = ref<PresalesEmployee[]>([]),
    employeeError = ref(''),
    employeesLoading = ref(false)
  let employeeRequest = 0
  const generation = ref<{ skill: PresalesSkill; taskGoal: string }>({ skill: 'S1', taskGoal: '' })

  let disposed = false
  function isActiveScope(scope: Scope) {
    return !disposed && options.isActiveScope(scope)
  }
  async function loadEmployees() {
    const scope = options.captureScope(),
      request = ++employeeRequest
    const ws = options.workspaceId()
    if (disposed || !ws) return
    const active = () => isActiveScope(scope) && request === employeeRequest
    employees.value = []
    employeeError.value = ''
    employeesLoading.value = true
    try {
      const result = await presalesApi.employees(ws)
      if (active()) employees.value = result
    } catch (e) {
      if (active()) employeeError.value = options.employeeIssue(presalesError(e).message)
    } finally {
      if (active()) employeesLoading.value = false
    }
  }
  async function openGeneration(
    skill: PresalesSkill,
    taskGoal = options.t('presales.context_message_30'),
  ) {
    if (disposed || options.isDirty() || options.saving.value || !options.canGenerate()) return
    generation.value.skill = skill
    generation.value.taskGoal = taskGoal
    generationOpen.value = true
    await loadEmployees()
  }
  async function continueEmployee() {
    await openGeneration('S2', options.t('presales.context_message_31'))
  }
  async function generate() {
    const scope = options.captureScope()
    const ws = options.workspaceId(),
      current = options.project.value
    if (
      disposed ||
      !ws ||
      !current ||
      options.isDirty() ||
      options.saving.value ||
      !options.canGenerate()
    )
      return
    options.saving.value = true
    const operationId = options.receipt({
      ws,
      id: current.id,
      version: current.version,
      generation: generation.value,
    })
    try {
      const result = await presalesApi.generate(ws, current.id, {
        ...generation.value,
        expectedVersion: current.version,
        operationId,
      })
      if (isActiveScope(scope) && options.acceptMutation(result, scope)) {
        options.startPolling()
        generationOpen.value = false
      }
    } catch (e) {
      if (!isActiveScope(scope)) return
      const issue = presalesError(e)
      options.error.value = employeeError.value = options.employeeIssue(issue.message)
      options.conflict.value = issue.conflict
    } finally {
      if (isActiveScope(scope)) options.saving.value = false
    }
  }
  async function cancelTask(task: PresalesTask) {
    const scope = options.captureScope()
    const ws = options.workspaceId(),
      id = options.projectId()
    if (
      disposed ||
      !ws ||
      !id ||
      !options.canWrite() ||
      options.saving.value ||
      task.status !== 'RUNNING'
    )
      return
    options.saving.value = true
    try {
      const result = await presalesApi.cancelTask(ws, id, task.id, {
        operationId: options.receipt({ ws, id, taskId: task.id, action: 'cancel' }),
      })
      if (isActiveScope(scope)) options.acceptMutation(result, scope)
    } catch (e) {
      if (!isActiveScope(scope)) return
      const issue = presalesError(e)
      options.error.value = employeeError.value = options.employeeIssue(issue.message)
      options.conflict.value = issue.conflict
    } finally {
      if (isActiveScope(scope)) options.saving.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
    employeeRequest++
  })
  return {
    employees,
    employeeError,
    employeesLoading,
    generation,
    generationOpen,
    loadEmployees,
    openGeneration,
    continueEmployee,
    generate,
    cancelTask,
  }
}
