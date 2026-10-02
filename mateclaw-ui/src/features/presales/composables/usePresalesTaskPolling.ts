import { onScopeDispose, type Ref } from 'vue'
import type { PresalesProject } from '../api/presalesApi'
import { isCurrentRequest, presalesError } from '../shared/state'

interface PollingOptions {
  workspaceId: () => string | null
  projectId: () => string
  project: Ref<PresalesProject | undefined>
  error: Ref<string>
  read: (workspaceId: string, projectId: string, signal: AbortSignal) => Promise<PresalesProject>
}
interface PollCycle {
  workspaceId: string
  projectId: string
  controller: AbortController
}

function waitForPoll(signal: AbortSignal): Promise<boolean> {
  if (signal.aborted) return Promise.resolve(false)
  return new Promise((resolve) => {
    const finish = (ready: boolean) => {
      window.clearTimeout(timer)
      signal.removeEventListener('abort', abort)
      resolve(ready)
    }
    const abort = () => finish(false)
    const timer = window.setTimeout(() => finish(true), 1000)
    signal.addEventListener('abort', abort, { once: true })
  })
}

/** One scoped read loop for all running operations; transport cancellation alone is insufficient. */
export function usePresalesTaskPolling(options: PollingOptions) {
  let cycle: PollCycle | undefined

  function isActive(current: PollCycle) {
    return (
      cycle === current &&
      !current.controller.signal.aborted &&
      isCurrentRequest(
        current.workspaceId,
        options.workspaceId(),
        current.projectId,
        options.projectId(),
      )
    )
  }
  function reset() {
    const previous = cycle
    cycle = undefined
    previous?.controller.abort()
  }
  async function poll(current: PollCycle) {
    const signal = current.controller.signal
    try {
      for (let attempt = 0; attempt < 600; attempt++) {
        if (!(await waitForPoll(signal)) || !isActive(current)) return
        const detail = await options.read(current.workspaceId, current.projectId, signal)
        if (!isActive(current)) return
        const accepted = options.project.value
        if (
          accepted &&
          (detail.version < accepted.version ||
            (accepted.sourceAccessRestricted && !detail.sourceAccessRestricted))
        )
          continue
        options.project.value = detail
        if (!detail.tasks?.some((task) => task.status === 'RUNNING' && task.operationId)) return
      }
    } catch (error) {
      if (isActive(current)) {
        const issue = presalesError(error)
        if (issue.accessDenied) options.project.value = undefined
        options.error.value = issue.message
      }
    } finally {
      if (cycle === current) cycle = undefined
    }
  }
  function start() {
    const workspaceId = options.workspaceId(),
      projectId = options.projectId()
    if (!workspaceId || !projectId) return
    if (cycle && isActive(cycle)) return
    reset()
    const current: PollCycle = {
      workspaceId,
      projectId,
      controller: new AbortController(),
    }
    cycle = current
    void poll(current)
  }
  onScopeDispose(reset)
  return { start, reset }
}
