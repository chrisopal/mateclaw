import { onScopeDispose, type Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  presalesApi,
  type PresalesCommandIntent,
  type PresalesEditorForm,
  type PresalesProject,
  type PresalesRecord,
} from '../api/presalesApi'
import { prepareEditorSubmission, type PresalesEditorKind } from '../shared/editorSubmission'
import { presalesError } from '../shared/state'

interface MutationEditor {
  editorKind: Ref<PresalesEditorKind>
  form: Ref<PresalesEditorForm>
  editorOpen: Ref<boolean>
  captureSession: () => () => boolean
}

interface MutationSessionOptions<Scope> {
  project: Ref<PresalesProject | undefined>
  saving: Ref<boolean>
  error: Ref<string>
  conflict: Ref<boolean>
  editError: Ref<string>
  workspaceId: () => string | null
  projectId: () => string
  canWrite: () => boolean
  canApprove: () => boolean
  captureScope: () => Scope
  isActiveScope: (scope: Scope) => boolean
  receipt: (input: unknown) => string
  acceptMutation: (detail: PresalesProject, scope: Scope) => boolean
  editor: MutationEditor
  continueEmployee: () => Promise<void>
  load: () => Promise<void>
  navigateToProject: (id: string) => Promise<unknown>
  t: (key: string) => string
}

/** Owns versioned workbench writes while the page retains scope and display state. */
export function usePresalesMutationSession<Scope>(options: MutationSessionOptions<Scope>) {
  let disposed = false

  function isActive(scope: Scope) {
    return !disposed && options.isActiveScope(scope)
  }

  async function command(intent: PresalesCommandIntent): Promise<boolean> {
    if (disposed) return false
    const { action, payload } = intent
    const scope = options.captureScope()
    const ws = options.workspaceId(),
      current = options.project.value
    if (!ws || !current || !options.canWrite() || options.saving.value) return false
    if (
      current.sourceAccessRestricted &&
      !(
        action === 'BIND_MATERIAL' ||
        action === 'UNBIND_MATERIAL' ||
        (action === 'UPDATE_PROJECT' &&
          Object.keys(payload).length === 1 &&
          'agentId' in payload &&
          typeof payload.agentId === 'string')
      )
    )
      return false
    options.saving.value = true
    options.editError.value = ''
    options.error.value = ''
    try {
      const result = await presalesApi.command(ws, current.id, {
        ...intent,
        expectedVersion: current.version,
        operationId: options.receipt({
          ws,
          id: current.id,
          version: current.version,
          action,
          payload,
        }),
      })
      return isActive(scope) && options.acceptMutation(result, scope)
    } catch (e) {
      if (!isActive(scope)) return false
      const issue = presalesError(e)
      options.conflict.value = issue.conflict
      options.error.value = options.editError.value = issue.message
      return false
    } finally {
      if (isActive(scope)) options.saving.value = false
    }
  }

  async function save() {
    if (disposed) return
    if (!options.canWrite() || options.saving.value || options.conflict.value) return
    const scope = options.captureScope(),
      active = options.editor.captureSession(),
      kind = options.editor.editorKind.value
    const submission = prepareEditorSubmission(
      kind,
      options.editor.form.value,
      !!options.project.value?.sourceAccessRestricted,
    )
    if (submission.kind === 'invalid') {
      options.editError.value = options.t(
        submission.issue === 'REQUIRED_FIELDS'
          ? 'presales.complete_the_required_fields'
          : 'presales.context_message_28',
      )
      return
    }
    if (submission.kind === 'project') {
      const data = submission.data
      const ws = options.workspaceId()
      if (!ws) return
      options.saving.value = true
      try {
        const body = {
          ...submission.metadata,
          expectedVersion: options.project.value?.version || 0,
          operationId: options.receipt({
            ws,
            id: options.projectId(),
            version: options.project.value?.version || 0,
            data,
          }),
        }
        const result = options.project.value
          ? await presalesApi.update(ws, options.project.value.id, body)
          : await presalesApi.create(ws, { ...body, expectedVersion: 0 })
        if (!active() || !isActive(scope) || !options.acceptMutation(result, scope)) return
        options.editor.editorOpen.value = false
        options.saving.value = false
        if (options.projectId()) await options.load()
        else await options.navigateToProject(result.id)
      } catch (e) {
        if (!active() || !isActive(scope)) return
        const issue = presalesError(e)
        options.editError.value = options.error.value = issue.message
        options.conflict.value = issue.conflict
      } finally {
        if (isActive(scope)) options.saving.value = false
      }
      return
    }
    const { kind: submissionKind, continueEmployee: resumeEmployee, ...intent } = submission
    if (submissionKind === 'command' && (await command(intent)) && active()) {
      options.editor.editorOpen.value = false
      if (resumeEmployee && options.project.value?.agentId) await options.continueEmployee()
    }
  }

  async function approveRelease(release: PresalesRecord) {
    if (disposed) return
    const scope = options.captureScope(),
      current = options.project.value
    if (!current || !options.canApprove() || options.saving.value) return
    try {
      const result = await ElMessageBox.prompt(
        options.t('presales.context_message_29'),
        options.t('presales.approve_release'),
        { inputValidator: (value) => !!value?.trim() },
      )
      if (
        !isActive(scope) ||
        current.id !== options.project.value?.id ||
        current.version !== options.project.value.version ||
        !options.canApprove()
      )
        return
      await command({
        action: 'APPROVE_RELEASE',
        payload: { releaseId: release.id, reason: result.value },
      })
    } catch {
      /* Cancel. */
    }
  }

  async function archive() {
    if (disposed) return
    const scope = options.captureScope(),
      current = options.project.value
    if (!current || !options.canWrite() || current.sourceAccessRestricted || options.saving.value)
      return
    try {
      await ElMessageBox.confirm(
        options.t('presales.archive_this_project_and_make_it_read_only'),
        options.t('presales.archive_project'),
        { type: 'warning' },
      )
      if (
        !isActive(scope) ||
        current.id !== options.project.value?.id ||
        current.version !== options.project.value.version ||
        !options.canWrite() ||
        options.project.value.sourceAccessRestricted
      )
        return
      await command({ action: 'ARCHIVE', payload: {} })
    } catch {
      /* Cancel leaves data unchanged. */
    }
  }

  onScopeDispose(() => {
    disposed = true
  })

  return { command, save, approveRelease, archive }
}
