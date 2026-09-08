import { createApp, h, nextTick, ref } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { extractionApi, type ExtractionTask } from '../../api/extractionApi'
import { useExtractionTask } from '../useExtractionTask'
vi.mock('../../api/extractionApi', () => ({ extractionApi: { read: vi.fn(), suggestions: vi.fn() } }))
let dispose: (() => void) | undefined
afterEach(() => { dispose?.(); vi.clearAllMocks() })
it('restores by task id without starting another task and rejects old workspace responses', async () => {
  let resolveOld!: (task: ExtractionTask) => void
  vi.mocked(extractionApi.read).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  vi.mocked(extractionApi.read).mockResolvedValue({ id: 'new', status: 'FAILED', sourceText: 'new source' } as ExtractionTask)
  const ws = ref('old'), graph = ref('graph'), id = ref('task')
  let state!: ReturnType<typeof useExtractionTask>
  const app = createApp({ setup() { state = useExtractionTask(ws, graph, id); return () => h('div') } })
  const host = document.createElement('div'); app.mount(host); dispose = () => app.unmount()
  await nextTick(); ws.value = 'new'; await nextTick(); await new Promise(r => setTimeout(r, 0))
  expect(state.task.value?.sourceText).toBe('new source')
  resolveOld({ id: 'old', status: 'SUCCEEDED', sourceText: 'private old source' } as ExtractionTask)
  await new Promise(r => setTimeout(r, 0))
  expect(state.task.value?.sourceText).toBe('new source')
  expect(extractionApi.suggestions).not.toHaveBeenCalled()
})
