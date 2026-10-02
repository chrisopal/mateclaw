import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import {
  presalesApi,
  type PresalesEditorForm,
  type PresalesProject,
  type PresalesRecord,
  type PresalesSource,
  type PresalesTrustedStatement,
} from '../api/presalesApi'
import {
  createEditorForm,
  requirementsForBaseline,
  solutionResponses,
  type PresalesEditorKind,
} from '../shared/editorSubmission'
import { presalesError } from '../shared/state'

interface EditorSessionOptions<Scope> {
  project: Ref<PresalesProject | undefined>
  canWrite: () => boolean
  saving: Ref<boolean>
  workspaceId: () => string | null
  projectId: () => string
  captureScope: () => Scope
  isActiveScope: (scope: Scope) => boolean
  loadEmployees: () => Promise<void>
  confirmDiscard: () => Promise<unknown>
}

/** Draft and option results belong to one editor opening within the page scope. */
export function usePresalesEditorSession<Scope>(options: EditorSessionOptions<Scope>) {
  const sourceOptions = ref<PresalesSource[]>([]),
    statementOptions = ref<PresalesTrustedStatement[]>([]),
    optionsLoading = ref(false)
  const editorOpen = ref(false),
    editorKind = ref<PresalesEditorKind>('project'),
    form = ref<PresalesEditorForm>({}),
    editError = ref(''),
    initialForm = ref('')
  let generation = 0,
    disposed = false

  watch(
    editorOpen,
    (open) => {
      if (!open) {
        generation++
        sourceOptions.value = []
        statementOptions.value = []
        optionsLoading.value = false
      }
    },
    { flush: 'sync' },
  )
  const editableRequirements = computed(() =>
    requirementsForBaseline(options.project.value, form.value.baselineId),
  )
  watch(
    () => form.value.baselineId,
    () => {
      if (editorKind.value !== 'solution' || !editorOpen.value) return
      form.value.requirementResponses = solutionResponses(form.value, options.project.value)
    },
  )
  const dirty = computed(() => editorOpen.value && JSON.stringify(form.value) !== initialForm.value)

  function captureSession() {
    const scope = options.captureScope(),
      session = generation
    return () =>
      !disposed && options.isActiveScope(scope) && session === generation && editorOpen.value
  }

  function openEditor(kind: PresalesEditorKind, record?: PresalesRecord) {
    if (
      disposed ||
      !options.canWrite() ||
      options.saving.value ||
      (options.project.value?.sourceAccessRestricted && !['employee', 'material'].includes(kind))
    )
      return
    generation++
    editorKind.value = kind
    editError.value = ''
    if (kind === 'project' || kind === 'employee') void options.loadEmployees()
    form.value = createEditorForm(kind, record, options.project.value)
    initialForm.value = JSON.stringify(form.value)
    editorOpen.value = true
    if (kind === 'material' || kind === 'requirement') void loadOptions(kind)
  }

  async function loadOptions(kind: PresalesEditorKind) {
    const current = captureSession()
    const ws = options.workspaceId(),
      id = options.projectId()
    if (!ws) return
    const active = () => current() && editorKind.value === kind
    optionsLoading.value = true
    sourceOptions.value = []
    statementOptions.value = []
    try {
      const [sources, statements] =
        kind === 'material'
          ? [await presalesApi.sources(ws), undefined]
          : [undefined, await presalesApi.statements(ws, id)]
      if (!active() || (kind !== 'material' && options.project.value?.sourceAccessRestricted))
        return
      if (sources) sourceOptions.value = sources
      if (statements) statementOptions.value = statements
    } catch (e) {
      if (active()) editError.value = presalesError(e).message
    } finally {
      if (active()) optionsLoading.value = false
    }
  }

  function selectSource(kbId: string) {
    const source = sourceOptions.value.find((item) => item.kbId === kbId)
    form.value.graphId = source?.graphId || ''
    form.value.name = source?.name || ''
  }
  function selectStatement(key: string) {
    const statement = statementOptions.value.find((item) => `${item.graphId}:${item.id}` === key)
    form.value.statementId = statement?.id || ''
    form.value.statementRevision = statement?.revision || ''
    form.value.graphId = statement?.graphId || ''
    form.value.evidenceIds = statement?.evidenceIds?.filter((id): id is string => id !== null) || []
  }

  async function discard(): Promise<boolean> {
    if (options.saving.value) return false
    if (!dirty.value) {
      editorOpen.value = false
      return true
    }
    const current = captureSession(),
      draft = JSON.stringify(form.value)
    try {
      await options.confirmDiscard()
      if (!current() || options.saving.value || draft !== JSON.stringify(form.value)) return false
      editorOpen.value = false
      return true
    } catch {
      return false
    }
  }
  async function closeEditor(done: () => void) {
    if ((await discard()) && !editorOpen.value) done()
  }

  watch(
    () => options.project.value,
    (current, previous) => {
      if (current && !current.sourceAccessRestricted) return
      if (!current && !previous) return
      statementOptions.value = []
      if (!current || !['employee', 'material'].includes(editorKind.value)) {
        editorOpen.value = false
        form.value = {}
        initialForm.value = ''
      }
    },
    { flush: 'sync' },
  )
  function beforeUnload(event: BeforeUnloadEvent) {
    if (dirty.value || options.saving.value) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  window.addEventListener('beforeunload', beforeUnload)
  onScopeDispose(() => {
    disposed = true
    generation++
    window.removeEventListener('beforeunload', beforeUnload)
  })

  return {
    sourceOptions,
    statementOptions,
    optionsLoading,
    editorOpen,
    editorKind,
    form,
    editError,
    editableRequirements,
    dirty,
    openEditor,
    selectSource,
    selectStatement,
    discard,
    closeEditor,
    captureSession,
  }
}
