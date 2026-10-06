import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { presalesApi, type PresalesProject, type PresalesRecord } from '../api/presalesApi'
import { isCurrentRequest, presalesError } from '../shared/state'

interface PreviewOptions {
  workspaceId: () => string | null
  projectId: () => string
  project: Ref<PresalesProject | undefined>
  error: Ref<string>
}

/** Source views belong to the current page and selection, not just the project ID. */
export function usePresalesSourcePreview(options: PreviewOptions) {
  const evidenceOpen = ref(false),
    evidence = ref<PresalesRecord>()
  const presentationPreview = ref({ open: false, url: '', title: '' })
  let evidenceRequest = 0,
    presentationRequest = 0,
    disposed = false

  function capture() {
    return {
      workspaceId: options.workspaceId(),
      projectId: options.projectId(),
      project: options.project.value,
    }
  }
  function isCurrent(current: ReturnType<typeof capture>) {
    return (
      !disposed &&
      !!current.workspaceId &&
      !!current.project &&
      options.project.value === current.project &&
      !current.project.sourceAccessRestricted &&
      isCurrentRequest(
        current.workspaceId,
        options.workspaceId(),
        current.projectId,
        options.projectId(),
      )
    )
  }
  function clearEvidence() {
    evidenceRequest++
    evidence.value = undefined
    evidenceOpen.value = false
  }
  function clearPresentationPreview() {
    presentationRequest++
    if (presentationPreview.value.url) URL.revokeObjectURL(presentationPreview.value.url)
    presentationPreview.value = { open: false, url: '', title: '' }
  }
  function reset() {
    clearEvidence()
    clearPresentationPreview()
  }

  async function showEvidence(record: PresalesRecord) {
    const current = capture(),
      request = ++evidenceRequest
    if (!isCurrent(current)) return
    evidence.value = record
    evidenceOpen.value = true
    const evidenceId = record.evidenceIds?.[0] || record.evidenceRefs?.[0]
    if (!record.graphId || !evidenceId) return
    const active = () => request === evidenceRequest && evidenceOpen.value && isCurrent(current)
    try {
      const result = await presalesApi.evidence(
        current.workspaceId!,
        current.projectId,
        record.graphId,
        String(evidenceId),
      )
      if (active()) evidence.value = { ...record, sourceSnapshot: result }
    } catch (error) {
      if (active())
        evidence.value = {
          id: record.id,
          status: 'UNAVAILABLE',
          error: presalesError(error).message,
        }
    }
  }
  async function previewPresentation(presentation: PresalesRecord, filename: string) {
    const current = capture(),
      request = ++presentationRequest
    if (!isCurrent(current)) return
    const artifactId = String(presentation.artifactId || '')
    const solutionId = current.project!.solutions.find(
      (solution) => solution.presentation?.artifactId === artifactId,
    )?.id
    if (!solutionId || !filename) return
    const active = () => request === presentationRequest && isCurrent(current)
    try {
      const blob = await presalesApi.file(
        current.workspaceId!,
        current.projectId,
        solutionId,
        filename,
        'draft',
      )
      if (!active()) return
      if (presentationPreview.value.url) URL.revokeObjectURL(presentationPreview.value.url)
      presentationPreview.value = {
        open: true,
        url: URL.createObjectURL(new Blob([blob], { type: 'image/svg+xml' })),
        title: filename,
      }
    } catch (error) {
      if (active()) options.error.value = presalesError(error).message
    }
  }
  watch(
    evidenceOpen,
    (open) => {
      if (!open) clearEvidence()
    },
    { flush: 'sync' },
  )
  watch(
    () => presentationPreview.value.open,
    (open) => {
      if (!open) clearPresentationPreview()
    },
    { flush: 'sync' },
  )
  watch([options.workspaceId, options.projectId], reset, { flush: 'sync' })
  watch(
    options.project,
    (project) => {
      if (!project || project.sourceAccessRestricted) reset()
    },
    { flush: 'sync' },
  )
  onScopeDispose(() => {
    disposed = true
    reset()
  })
  return {
    evidenceOpen,
    evidence,
    presentationPreview,
    showEvidence,
    previewPresentation,
    clearPresentationPreview,
    reset,
  }
}
