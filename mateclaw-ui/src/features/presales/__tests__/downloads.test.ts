import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { presalesApi, type PresalesHandoff, type PresalesProject } from '../api/presalesApi'
import { usePresalesDownloads } from '../composables/usePresalesDownloads'

vi.mock('../api/presalesApi', () => ({
  presalesApi: { file: vi.fn(), handoff: vi.fn() },
}))
const detail: PresalesProject = {
  id: '90071992547409998',
  workspaceId: '90071992547409999',
  version: 3,
  name: 'Plant',
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
  tasks: [],
}
const handoff: PresalesHandoff = {
  schemaVersion: 1,
  engagementId: detail.id,
  caseRef: detail.id,
  workspaceId: detail.workspaceId,
  baseline: { id: 'baseline-1' },
  solution: { id: 'solution-1' },
  release: { id: 'release-1' },
  fitGaps: [],
  clarifications: [],
  risksAndUnknowns: [],
  customerConfirmationStatus: 'UNCONFIRMED',
  accessPolicy: 'WORKSPACE_REAUTHORIZE_ON_READ',
  historicalExtension: { text: '保留原始内容', id: '90071992547409996' },
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
let scope: EffectScope
function setup() {
  const project = ref<PresalesProject | undefined>(structuredClone(detail))
  const error = ref(''),
    workspaceId = ref<string | null>(detail.workspaceId),
    projectId = ref(detail.id),
    generation = ref(0)
  const captureScope = () => ({
    ws: workspaceId.value,
    id: projectId.value,
    generation: generation.value,
  })
  scope = effectScope()
  const session = scope.run(() =>
    usePresalesDownloads({
      project,
      error,
      workspaceId: () => workspaceId.value,
      projectId: () => projectId.value,
      captureScope,
      isActiveScope: (captured) =>
        captured.ws === workspaceId.value &&
        captured.id === projectId.value &&
        captured.generation === generation.value,
    }),
  )!
  return { ...session, project, error, workspaceId, projectId, generation }
}
const createUrl = vi.fn<(blob: Blob | MediaSource) => string>(),
  revokeUrl = vi.fn<(url: string) => void>()
let clicked: HTMLAnchorElement[]
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  clicked = []
  createUrl.mockReturnValue('blob:presales-download')
  vi.spyOn(URL, 'createObjectURL').mockImplementation(createUrl)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revokeUrl)
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    clicked.push(this)
  })
})
afterEach(() => {
  scope?.stop()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('presales download lifecycle', () => {
  it.each(['files', 'preview', 'draft'] as const)(
    'preserves exact %s bytes, version ID, filename and delayed URL release',
    async (kind) => {
      const blob = new Blob([new Uint8Array([0, 255, 1, 2])], { type: 'application/pdf' })
      vi.mocked(presalesApi.file).mockResolvedValue(blob)
      const session = setup()
      await session.download('90071992547409995', '原方案.pdf', kind)
      expect(presalesApi.file).toHaveBeenCalledWith(
        detail.workspaceId,
        detail.id,
        '90071992547409995',
        '原方案.pdf',
        kind,
      )
      expect(createUrl).toHaveBeenCalledExactlyOnceWith(blob)
      expect(clicked).toHaveLength(1)
      expect(clicked[0]?.href).toBe('blob:presales-download')
      expect(clicked[0]?.download).toBe((kind === 'files' ? '' : 'UNAPPROVED-') + '原方案.pdf')
      scope.stop()
      await vi.advanceTimersByTimeAsync(999)
      expect(revokeUrl).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(1)
      expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:presales-download')
    },
  )

  it('exports the original handoff JSON and exact project version in its filename', async () => {
    vi.mocked(presalesApi.handoff).mockResolvedValue(handoff)
    const session = setup()
    await session.downloadHandoff()
    expect(presalesApi.handoff).toHaveBeenCalledExactlyOnceWith(detail.workspaceId, detail.id)
    const blob = createUrl.mock.calls[0]![0]
    expect(blob).toBeInstanceOf(Blob)
    if (!(blob instanceof Blob)) throw new Error('Expected a Blob export')
    expect(blob.type).toBe('application/json')
    expect(await blob.text()).toBe(JSON.stringify(handoff, null, 2))
    expect(clicked[0]?.download).toBe(`internal-handoff-${detail.id}-v3.json`)
    await vi.advanceTimersByTimeAsync(1000)
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:presales-download')
  })

  it.each(['workspace', 'restricted', 'disposed'] as const)(
    'does not issue either request after %s becomes unavailable',
    async (reason) => {
      const session = setup()
      if (reason === 'workspace') session.workspaceId.value = null
      if (reason === 'restricted') session.project.value!.sourceAccessRestricted = true
      if (reason === 'disposed') scope.stop()
      await session.download('release-1', 'solution.pdf', 'files')
      await session.downloadHandoff()
      expect(presalesApi.file).not.toHaveBeenCalled()
      expect(presalesApi.handoff).not.toHaveBeenCalled()
      expect(createUrl).not.toHaveBeenCalled()
      expect(session.error.value).toBe('')
    },
  )

  it.each(['file', 'handoff'] as const)(
    'shows a current %s error without producing a downloadable URL',
    async (kind) => {
      const denied = { response: { status: 403, data: { msg: 'Access revoked' } } }
      vi.mocked(presalesApi.file).mockRejectedValue(denied)
      vi.mocked(presalesApi.handoff).mockRejectedValue(denied)
      const session = setup()
      if (kind === 'file') await session.download('release-1', 'solution.pdf', 'files')
      else await session.downloadHandoff()
      expect(session.error.value).toBe('Access revoked')
      expect(createUrl).not.toHaveBeenCalled()
      expect(clicked).toHaveLength(0)
    },
  )

  for (const kind of ['file', 'handoff'] as const) {
    it.each([
      'workspace',
      'project',
      'replacement',
      'restriction',
      'generation',
      'dispose',
    ] as const)(`ignores a late ${kind} result after %s changes`, async (reason) => {
      const pendingFile = deferred<Blob>()
      const pendingHandoff = deferred<PresalesHandoff>()
      vi.mocked(presalesApi.file).mockReturnValue(pendingFile.promise)
      vi.mocked(presalesApi.handoff).mockReturnValue(pendingHandoff.promise)
      const session = setup()
      const request =
        kind === 'file'
          ? session.download('release-1', 'solution.pdf', 'files')
          : session.downloadHandoff()
      if (reason === 'workspace') session.workspaceId.value = 'another-workspace'
      if (reason === 'project') session.projectId.value = 'another-project'
      if (reason === 'replacement') session.project.value = structuredClone(detail)
      if (reason === 'restriction') session.project.value!.sourceAccessRestricted = true
      if (reason === 'generation') session.generation.value++
      if (reason === 'dispose') scope.stop()
      pendingFile.resolve(new Blob(['private']))
      pendingHandoff.resolve(handoff)
      await request
      expect(createUrl).not.toHaveBeenCalled()
      expect(clicked).toHaveLength(0)
      expect(session.error.value).toBe('')
    })
    it.each(['replacement', 'generation', 'dispose'] as const)(
      `keeps the current error after a stale ${kind} rejection following %s`,
      async (reason) => {
        const pending = deferred<never>()
        vi.mocked(presalesApi.file).mockReturnValue(pending.promise)
        vi.mocked(presalesApi.handoff).mockReturnValue(pending.promise)
        const session = setup()
        const request =
          kind === 'file'
            ? session.download('release-1', 'solution.pdf', 'files')
            : session.downloadHandoff()
        if (reason === 'replacement') session.project.value = structuredClone(detail)
        if (reason === 'generation') session.generation.value++
        if (reason === 'dispose') scope.stop()
        session.error.value = 'Current view error'
        pending.reject(new Error('Old view error'))
        await request
        expect(session.error.value).toBe('Current view error')
        expect(createUrl).not.toHaveBeenCalled()
      },
    )
  }
})
