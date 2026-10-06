import { onScopeDispose, type Ref } from 'vue'
import { presalesApi, type PresalesProject } from '../api/presalesApi'
import { isCurrentRequest, presalesError } from '../shared/state'

interface DownloadsOptions<Scope> {
  project: Ref<PresalesProject | undefined>
  error: Ref<string>
  workspaceId: () => string | null
  projectId: () => string
  captureScope: () => Scope
  isActiveScope: (scope: Scope) => boolean
}

/** Owns binary and handoff export lifecycles while preserving the page's source gate. */
export function usePresalesDownloads<Scope>(options: DownloadsOptions<Scope>) {
  let disposed = false

  function isCurrent(scope: Scope, current: PresalesProject | undefined, ws: string, id: string) {
    return (
      !disposed &&
      !!current &&
      options.isActiveScope(scope) &&
      options.project.value === current &&
      !current.sourceAccessRestricted &&
      isCurrentRequest(ws, options.workspaceId(), id, options.projectId())
    )
  }

  function downloadBlob(blob: Blob, filename: string) {
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  async function downloadHandoff() {
    if (disposed) return
    const scope = options.captureScope()
    const ws = options.workspaceId(),
      id = options.projectId()
    const current = options.project.value
    if (!ws || !current || current.sourceAccessRestricted) return
    try {
      const result = await presalesApi.handoff(ws, id)
      if (!isCurrent(scope, current, ws, id)) return
      downloadBlob(
        new Blob([JSON.stringify(result, null, 2)], {
          type: 'application/json',
        }),
        `internal-handoff-${id}-v${current.version}.json`,
      )
    } catch (e) {
      if (!disposed && options.isActiveScope(scope) && options.project.value === current)
        options.error.value = presalesError(e).message
    }
  }

  async function download(
    versionId: string,
    filename: string,
    kind: 'files' | 'preview' | 'draft',
  ) {
    if (disposed) return
    const scope = options.captureScope()
    const ws = options.workspaceId(),
      id = options.projectId()
    const current = options.project.value
    if (!ws || current?.sourceAccessRestricted) return
    try {
      const blob = await presalesApi.file(ws, id, versionId, filename, kind)
      if (!isCurrent(scope, current, ws, id)) return
      downloadBlob(blob, (kind === 'files' ? '' : 'UNAPPROVED-') + filename)
    } catch (e) {
      if (!disposed && options.isActiveScope(scope) && options.project.value === current)
        options.error.value = presalesError(e).message
    }
  }

  onScopeDispose(() => {
    disposed = true
  })

  return { download, downloadHandoff }
}
