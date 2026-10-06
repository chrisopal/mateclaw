import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  presalesApi,
  type PresalesEditorForm,
  type PresalesProject,
  type PresalesRecord,
} from '../api/presalesApi'
import { usePresalesMutationSession } from '../composables/usePresalesMutationSession'
import { operationReceipt } from '../shared/state'
import type { PresalesEditorKind } from '../shared/editorSubmission'

vi.mock('../api/presalesApi', () => ({
  presalesApi: {
    command: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
  },
}))

const detail: PresalesProject = {
  id: '90071992547409998',
  workspaceId: '90071992547409999',
  version: 3,
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
  tasks: [],
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
  const saving = ref(false),
    error = ref(''),
    conflict = ref(false),
    editError = ref(''),
    workspaceId = ref<string | null>(detail.workspaceId),
    projectId = ref(detail.id),
    generation = ref(0),
    canWrite = ref(true),
    canApprove = ref(true),
    editorOpen = ref(true),
    editorKind = ref<PresalesEditorKind>('material'),
    form = ref<PresalesEditorForm>({
      kbId: 'kb',
      graphId: 'graph',
      role: 'PROJECT',
    })
  const captureScope = () => ({
    workspaceId: workspaceId.value,
    projectId: projectId.value,
    generation: generation.value,
  })
  const isActiveScope = (value: ReturnType<typeof captureScope>) =>
    value.workspaceId === workspaceId.value &&
    value.projectId === projectId.value &&
    value.generation === generation.value
  const editorSession = () => editorOpen.value
  const acceptMutation = vi.fn((result: PresalesProject) => {
    project.value = result
    return true
  })
  const load = vi.fn<() => Promise<void>>().mockResolvedValue()
  const navigateToProject = vi.fn<(_id: string) => Promise<unknown>>().mockResolvedValue(undefined)
  scope = effectScope()
  const session = scope.run(() =>
    usePresalesMutationSession({
      project,
      saving,
      error,
      conflict,
      editError,
      workspaceId: () => workspaceId.value,
      projectId: () => projectId.value,
      canWrite: () => canWrite.value,
      canApprove: () => canApprove.value,
      captureScope,
      isActiveScope,
      receipt: operationReceipt(),
      acceptMutation,
      editor: {
        editorKind,
        form,
        editorOpen,
        captureSession: () => editorSession,
      },
      continueEmployee: vi.fn<() => Promise<void>>().mockResolvedValue(),
      load,
      navigateToProject,
      t: (key) => `translated:${key}`,
    }),
  )!
  return {
    ...session,
    project,
    saving,
    error,
    conflict,
    editError,
    workspaceId,
    projectId,
    generation,
    canWrite,
    canApprove,
    editorOpen,
    editorKind,
    form,
    load,
    navigateToProject,
    acceptMutation,
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(presalesApi.command).mockResolvedValue({ ...detail, version: 4 })
  vi.mocked(presalesApi.create).mockResolvedValue({
    ...detail,
    id: 'created',
    version: 1,
  })
  vi.mocked(presalesApi.update).mockResolvedValue({ ...detail, version: 4 })
})
afterEach(() => scope?.stop())

describe('presales mutation session ownership', () => {
  it('keeps exact command payload, version and receipt across a retry', async () => {
    vi.mocked(presalesApi.command).mockRejectedValueOnce({
      response: { status: 500, data: { msg: 'Temporary failure' } },
    })
    const session = setup()
    const intent = {
      action: 'BIND_MATERIAL' as const,
      payload: { kbId: 'kb', graphId: 'graph', role: 'PROJECT' as const },
    }
    expect(await session.command(intent)).toBe(false)
    expect(session.error.value).toBe('Temporary failure')
    expect(await session.command(intent)).toBe(true)
    expect(presalesApi.command).toHaveBeenCalledTimes(2)
    expect(vi.mocked(presalesApi.command).mock.calls[1]).toEqual(
      vi.mocked(presalesApi.command).mock.calls[0],
    )
    expect(vi.mocked(presalesApi.command).mock.calls[0][2]).toEqual({
      ...intent,
      expectedVersion: detail.version,
      operationId: expect.any(String),
    })
  })

  it('does not unlock a stale command after its page scope changes', async () => {
    const pending = deferred<PresalesProject>()
    vi.mocked(presalesApi.command).mockReturnValueOnce(pending.promise)
    const session = setup()
    const request = session.command({ action: 'ARCHIVE', payload: {} })
    expect(session.saving.value).toBe(true)
    session.generation.value++
    pending.resolve({ ...detail, version: 4, status: 'ARCHIVED' })
    expect(await request).toBe(false)
    expect(session.saving.value).toBe(true)
    expect(session.project.value?.status).toBe('ACTIVE')
  })

  it('submits a prepared editor command and closes only the active editor', async () => {
    const session = setup()
    await session.save()
    expect(presalesApi.command).toHaveBeenCalledWith(detail.workspaceId, detail.id, {
      action: 'BIND_MATERIAL',
      payload: { kbId: 'kb', graphId: 'graph', role: 'PROJECT' },
      expectedVersion: detail.version,
      operationId: expect.any(String),
    })
    expect(session.editorOpen.value).toBe(false)
    expect(session.saving.value).toBe(false)
  })

  it('requires the current approval confirmation before submitting its reason', async () => {
    const prompt = vi
      .spyOn(ElMessageBox, 'prompt')
      // Element Plus intersects Action with input data; runtime prompts return this object.
      .mockResolvedValue({ value: 'Reviewed', action: 'confirm' } as Awaited<
        ReturnType<typeof ElMessageBox.prompt>
      >)
    const release: PresalesRecord = { id: 'release-1', status: 'PENDING' }
    const session = setup()
    await session.approveRelease(release)
    expect(presalesApi.command).toHaveBeenCalledWith(detail.workspaceId, detail.id, {
      action: 'APPROVE_RELEASE',
      payload: { releaseId: release.id, reason: 'Reviewed' },
      expectedVersion: detail.version,
      operationId: expect.any(String),
    })
    prompt.mockRestore()
  })

  it.each(['create', 'update'] as const)(
    'closes and unlocks a successful %s before awaiting navigation or reload',
    async (action) => {
      const session = setup()
      session.editorKind.value = 'project'
      session.form.value = { name: 'Edited project', customer: 'Customer' }
      const pending = deferred<void>()
      if (action === 'create') {
        session.project.value = undefined
        session.projectId.value = ''
        session.navigateToProject.mockReturnValueOnce(pending.promise)
      } else session.load.mockReturnValueOnce(pending.promise)
      let finished = false
      const request = session.save().then(() => {
        finished = true
      })
      await Promise.resolve()
      await Promise.resolve()
      expect(presalesApi[action]).toHaveBeenCalledTimes(1)
      expect(session.editorOpen.value).toBe(false)
      expect(session.saving.value).toBe(false)
      expect(finished).toBe(false)
      if (action === 'create')
        expect(session.navigateToProject).toHaveBeenCalledExactlyOnceWith('created')
      else expect(session.load).toHaveBeenCalledOnce()
      // A new page scope can begin before the downstream work resolves.
      session.generation.value++
      session.saving.value = true
      pending.resolve()
      await request
      expect(finished).toBe(true)
      expect(session.saving.value).toBe(true)
    },
  )

  it('does not issue a mutation after the session scope is disposed', async () => {
    const session = setup()
    scope.stop()
    await session.command({ action: 'ARCHIVE', payload: {} })
    expect(presalesApi.command).not.toHaveBeenCalled()
  })
})
