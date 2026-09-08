import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { extractionApi, type ExtractionSuggestion, type ExtractionTask } from '../api/extractionApi'
export function useExtractionTask(workspaceId: Ref<string>, graphId: Ref<string>, taskId: Ref<string>) {
  const task = ref<ExtractionTask | null>(null), suggestions = ref<ExtractionSuggestion[]>([]), error = ref(''), loading = ref(false)
  let epoch = 0, request = 0, controller = new AbortController(), timer: ReturnType<typeof setTimeout> | undefined
  const clearTimer = () => { if (timer) clearTimeout(timer); timer = undefined }
  function schedule() {
    clearTimer()
    if (document.visibilityState !== 'hidden' && ['QUEUED', 'RUNNING'].includes(task.value?.status ?? '')) timer = setTimeout(() => { void refresh() }, 3000)
  }
  async function refresh() {
    clearTimer()
    if (!workspaceId.value || !graphId.value || !taskId.value) return
    const generation = epoch, call = ++request, ws = workspaceId.value, graph = graphId.value, id = taskId.value, signal = controller.signal
    const current = () => generation === epoch && call === request && !signal.aborted
    loading.value = true; error.value = ''
    try {
      const result = await extractionApi.read(ws, graph, id, signal)
      if (!current()) return
      let rows: ExtractionSuggestion[] = []
      if (result.status === 'SUCCEEDED') {
        for (let page = 1; page <= 2; page++) {
          const response = await extractionApi.suggestions(ws, graph, id, page, signal)
          if (!current()) return
          rows = rows.concat(response.items)
          if (rows.length >= (response.total ?? rows.length)) break
        }
      }
      task.value = result; suggestions.value = rows
    } catch (e) {
      if (current()) { error.value = (e as Error).message; task.value = null; suggestions.value = [] }
    } finally { if (current()) { loading.value = false; schedule() } }
  }
  function visibility() { if (document.visibilityState === 'hidden') clearTimer(); else if (['QUEUED', 'RUNNING'].includes(task.value?.status ?? '')) void refresh() }
  watch(() => [workspaceId.value, graphId.value, taskId.value], () => {
    epoch++; controller.abort(); controller = new AbortController(); clearTimer()
    task.value = null; suggestions.value = []; error.value = ''; loading.value = false
    void refresh()
  }, { immediate: true, flush: 'sync' })
  onMounted(() => document.addEventListener('visibilitychange', visibility))
  onBeforeUnmount(() => { epoch++; controller.abort(); clearTimer(); document.removeEventListener('visibilitychange', visibility) })
  return { task, suggestions, error, loading, refresh }
}
