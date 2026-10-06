import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import type { PresalesProject } from '../api/presalesApi'
import { usePresalesTaskPolling } from '../composables/usePresalesTaskPolling'

function project(version = 3): PresalesProject {
  return {
    id: '90071992547409998',
    workspaceId: '90071992547409999',
    version,
    name: 'Plant A',
    customer: 'Customer',
    ownerId: '90071992547409997',
    status: 'ACTIVE',
    materials: [],
    requirements: [],
    clarifications: [],
    baselines: [],
    fitGaps: [],
    solutions: [],
    reviews: [],
    releases: [],
    tasks: [{ id: 'task-1', operationId: 'op-1', status: 'RUNNING' }],
  }
}
function deferred() {
  let resolve!: (value: PresalesProject) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<PresalesProject>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
let scope: EffectScope
function setup() {
  const current = ref<PresalesProject | undefined>(project())
  const error = ref('')
  const workspaceId = ref<string | null>(current.value!.workspaceId)
  const projectId = ref(current.value!.id)
  const read = vi
    .fn<(ws: string, id: string, signal: AbortSignal) => Promise<PresalesProject>>()
    .mockResolvedValue(project())
  scope = effectScope()
  const polling = scope.run(() =>
    usePresalesTaskPolling({
      workspaceId: () => workspaceId.value,
      projectId: () => projectId.value,
      project: current,
      error,
      read,
    }),
  )!
  return { ...polling, current, error, workspaceId, projectId, read }
}
beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  scope?.stop()
  vi.useRealTimers()
})

describe('presales project polling lifecycle', () => {
  it('deduplicates starts and never overlaps reads, even with multiple running tasks', async () => {
    const state = setup(),
      pending = deferred()
    state.current.value!.tasks!.push({
      id: 'task-2',
      operationId: 'op-2',
      status: 'RUNNING',
    })
    state.read.mockReturnValueOnce(pending.promise)
    state.start()
    state.start()
    await vi.advanceTimersByTimeAsync(5000)
    expect(state.read).toHaveBeenCalledTimes(1)
    expect(state.read.mock.calls[0].slice(0, 2)).toEqual([project().workspaceId, project().id])
    pending.resolve(project(4))
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(999)
    expect(state.read).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(state.read).toHaveBeenCalledTimes(2)
  })

  it('releases a scheduled timer on reset and scope disposal', async () => {
    const state = setup()
    state.start()
    expect(vi.getTimerCount()).toBe(1)
    state.reset()
    expect(vi.getTimerCount()).toBe(0)
    state.start()
    expect(vi.getTimerCount()).toBe(1)
    scope.stop()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(2000)
    expect(state.read).not.toHaveBeenCalled()
  })

  it.each(['success', '403'] as const)(
    'ignores a late %s after reset without stopping its replacement',
    async (outcome) => {
      const state = setup(),
        pending = deferred()
      state.read.mockReturnValueOnce(pending.promise)
      state.start()
      await vi.advanceTimersByTimeAsync(1000)
      const oldSignal = state.read.mock.calls[0][2]
      state.reset()
      state.current.value = project(4)
      state.error.value = 'Current error'
      state.read.mockResolvedValue(project(4))
      state.start()
      expect(oldSignal.aborted).toBe(true)
      if (outcome === 'success') pending.resolve(project(3))
      else
        pending.reject({
          response: { status: 403, data: { msg: 'Late denied' } },
        })
      await vi.advanceTimersByTimeAsync(2000)
      expect(state.current.value?.version).toBe(4)
      expect(state.error.value).toBe('Current error')
      expect(state.read).toHaveBeenCalledTimes(3)
    },
  )

  it('aborts an in-flight read on scope disposal and ignores its late result', async () => {
    const state = setup(),
      pending = deferred()
    state.read.mockReturnValueOnce(pending.promise)
    state.start()
    await vi.advanceTimersByTimeAsync(1000)
    scope.stop()
    expect(state.read.mock.calls[0][2].aborted).toBe(true)
    pending.resolve(project(4))
    await vi.advanceTimersByTimeAsync(2000)
    expect(state.current.value?.version).toBe(3)
    expect(state.read).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('rejects a lower version and waits for a current response', async () => {
    const state = setup(),
      pending = deferred()
    state.read.mockReturnValueOnce(pending.promise)
    state.start()
    await vi.advanceTimersByTimeAsync(1000)
    state.current.value = project(5)
    pending.resolve({ ...project(4), tasks: [] })
    await vi.advanceTimersByTimeAsync(0)
    expect(state.current.value?.version).toBe(5)
    state.read.mockResolvedValue({ ...project(6), tasks: [] })
    await vi.advanceTimersByTimeAsync(1000)
    expect(state.current.value?.version).toBe(6)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cannot restore unrestricted data within a restricted cycle', async () => {
    const state = setup(),
      pending = deferred()
    state.read.mockReturnValueOnce(pending.promise)
    state.start()
    await vi.advanceTimersByTimeAsync(1000)
    state.current.value = { ...project(), sourceAccessRestricted: true }
    pending.resolve({ ...project(4), context: 'Private source' })
    await vi.advanceTimersByTimeAsync(0)
    expect(state.current.value?.sourceAccessRestricted).toBe(true)
    expect(state.current.value?.context).toBeUndefined()
    state.read.mockResolvedValue({
      ...project(5),
      sourceAccessRestricted: true,
      tasks: [],
    })
    await vi.advanceTimersByTimeAsync(1000)
    expect(state.current.value?.version).toBe(5)
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each(['workspaceId', 'projectId'] as const)(
    'ignores a response after %s changes',
    async (key) => {
      const state = setup(),
        pending = deferred()
      state.read.mockReturnValueOnce(pending.promise)
      state.start()
      await vi.advanceTimersByTimeAsync(1000)
      state[key].value = 'different'
      pending.resolve(project(4))
      await vi.advanceTimersByTimeAsync(2000)
      expect(state.current.value?.version).toBe(3)
      expect(state.read).toHaveBeenCalledTimes(1)
      expect(vi.getTimerCount()).toBe(0)
    },
  )

  it('clears project data on a current 403 and preserves the server message', async () => {
    const state = setup()
    state.read.mockRejectedValue({
      response: { status: 403, data: { msg: 'Source denied' } },
    })
    state.start()
    await vi.advanceTimersByTimeAsync(2000)
    expect(state.current.value).toBeUndefined()
    expect(state.error.value).toBe('Source denied')
    expect(state.read).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('stops after 600 serial reads with no remaining timer', async () => {
    const state = setup()
    state.start()
    await vi.advanceTimersByTimeAsync(601000)
    expect(state.read).toHaveBeenCalledTimes(600)
    expect(vi.getTimerCount()).toBe(0)
  })
})
