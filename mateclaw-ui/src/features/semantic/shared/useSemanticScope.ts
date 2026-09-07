import { onScopeDispose, watch } from 'vue'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
/** Each invocation invalidates older work; mutations retain their initiating scope. */
export function useSemanticScope() {
  const workspace = useWorkspaceStore()
  let generation = 0,
    controller: AbortController | null = null
  function cancel() {
    generation++
    controller?.abort()
  }
  function begin() {
    cancel()
    controller = new AbortController()
    const run = generation,
      id = workspace.currentWorkspaceId ?? ''
    return {
      id,
      signal: controller.signal,
      current: () => run === generation && id === workspace.currentWorkspaceId,
    }
  }
  watch(() => workspace.currentWorkspaceId, cancel, { flush: 'sync' })
  onScopeDispose(cancel)
  return { workspace, begin, cancel }
}
