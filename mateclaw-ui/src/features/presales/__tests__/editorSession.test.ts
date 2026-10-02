import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref, type EffectScope } from 'vue'
import { presalesApi, type PresalesProject, type PresalesSource } from '../api/presalesApi'
import { usePresalesEditorSession } from '../composables/usePresalesEditorSession'

vi.mock('../api/presalesApi', () => ({ presalesApi: { sources: vi.fn(), statements: vi.fn() } }))
function project(): PresalesProject {
  return {
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
  const current = ref<PresalesProject | undefined>(project())
  const canWrite = ref(true),
    saving = ref(false),
    scopeGeneration = ref(0)
  const loadEmployees = vi.fn<() => Promise<void>>().mockResolvedValue()
  const confirmDiscard = vi.fn<() => Promise<unknown>>().mockResolvedValue('confirm')
  scope = effectScope()
  const editor = scope.run(() =>
    usePresalesEditorSession({
      project: current,
      canWrite: () => canWrite.value,
      saving,
      workspaceId: () => current.value?.workspaceId || null,
      projectId: () => current.value?.id || '',
      captureScope: () => scopeGeneration.value,
      isActiveScope: (value) => value === scopeGeneration.value,
      loadEmployees,
      confirmDiscard,
    }),
  )!
  return { ...editor, current, canWrite, saving, scopeGeneration, loadEmployees, confirmDiscard }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(presalesApi.sources).mockResolvedValue([])
  vi.mocked(presalesApi.statements).mockResolvedValue([])
})
afterEach(() => scope?.stop())

async function settle() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
}

describe('presales editor session ownership', () => {
  it('invalidates captures on close/reopen without exposing its generation', () => {
    const editor = setup()
    editor.openEditor('project')
    const previous = editor.captureSession()
    expect(previous()).toBe(true)
    editor.editorOpen.value = false
    editor.openEditor('project')
    expect(previous()).toBe(false)
    expect(editor.captureSession()()).toBe(true)
    expect(editor.loadEmployees).toHaveBeenCalledTimes(2)
  })

  it('rejects captures after leaving and returning to identical page IDs', () => {
    const editor = setup()
    editor.openEditor('project')
    const previous = editor.captureSession()
    editor.scopeGeneration.value += 2
    expect(previous()).toBe(false)
  })

  it('does not open or confirm discard while a mutation is busy', async () => {
    const editor = setup()
    editor.saving.value = true
    editor.openEditor('project')
    expect(editor.editorOpen.value).toBe(false)
    expect(await editor.discard()).toBe(false)
    expect(editor.loadEmployees).not.toHaveBeenCalled()
    expect(editor.confirmDiscard).not.toHaveBeenCalled()
  })

  it('refuses editor opening without write capability', () => {
    const editor = setup()
    editor.canWrite.value = false
    editor.openEditor('project')
    expect(editor.editorOpen.value).toBe(false)
    expect(editor.loadEmployees).not.toHaveBeenCalled()
  })

  it('keeps only employee/material repair entry points when source access is restricted', () => {
    const editor = setup()
    editor.current.value = { ...project(), sourceAccessRestricted: true }
    editor.openEditor('project')
    expect(editor.editorOpen.value).toBe(false)
    editor.openEditor('employee')
    expect(editor.editorKind.value).toBe('employee')
    editor.openEditor('requirement')
    expect(editor.editorKind.value).toBe('employee')
    editor.openEditor('material')
    expect(editor.editorKind.value).toBe('material')
  })

  it.each(['success', 'failure'] as const)(
    'does not let a stale option %s clear replacement busy state or content',
    async (outcome) => {
      const old = deferred<PresalesSource[]>(),
        replacement = deferred<PresalesSource[]>()
      vi.mocked(presalesApi.sources)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(replacement.promise)
      const editor = setup()
      editor.openEditor('material')
      editor.editorOpen.value = false
      editor.openEditor('material')
      if (outcome === 'success') old.resolve([{ kbId: 'old', name: 'Old option' }])
      else old.reject(new Error('Old failure'))
      await settle()
      expect(editor.optionsLoading.value).toBe(true)
      expect(editor.sourceOptions.value).toEqual([])
      expect(editor.editError.value).toBe('')
      replacement.resolve([
        {
          kbId: '90071992547409999',
          graphId: 'graph-new',
          ontologyRevisionId: null,
          name: 'Current option',
        },
      ])
      await settle()
      expect(editor.optionsLoading.value).toBe(false)
      editor.selectSource('90071992547409999')
      expect(editor.form.value).toMatchObject({
        kbId: '',
        graphId: 'graph-new',
        name: 'Current option',
      })
    },
  )

  it('keeps raw nullable fact evidence while admitting only actual IDs into a selected draft', async () => {
    const raw = {
      id: 'fact',
      graphId: 'graph',
      ontologyRevisionId: null,
      label: 'Fact',
      revision: 3,
      evidenceIds: ['e2', null, 'e1', 'e2'],
    }
    vi.mocked(presalesApi.statements).mockResolvedValue([raw])
    const editor = setup()
    editor.openEditor('requirement')
    await settle()
    editor.selectStatement('graph:fact')
    expect(editor.form.value.evidenceIds).toEqual(['e2', 'e1', 'e2'])
    expect(editor.statementOptions.value[0].evidenceIds).toEqual(['e2', null, 'e1', 'e2'])
    expect(raw.evidenceIds).toEqual(['e2', null, 'e1', 'e2'])
    expect(editor.form.value).toMatchObject({
      statementId: 'fact',
      statementRevision: 3,
      graphId: 'graph',
    })
  })

  it('preserves null evidence-list semantics without copying null into the editor', async () => {
    const raw = {
      id: 'fact',
      graphId: 'graph',
      ontologyRevisionId: null,
      label: 'Fact',
      revision: 3,
      evidenceIds: null,
    }
    vi.mocked(presalesApi.statements).mockResolvedValue([raw])
    const editor = setup()
    editor.openEditor('requirement')
    await settle()
    editor.selectStatement('graph:fact')
    expect(editor.form.value.evidenceIds).toEqual([])
    expect(editor.statementOptions.value[0].evidenceIds).toBeNull()
  })

  it('copies exact statement identity/revision/evidence from the current option selection', async () => {
    vi.mocked(presalesApi.statements).mockResolvedValue([
      {
        id: '90071992547409999',
        graphId: 'graph',
        ontologyRevisionId: null,
        label: 'Fact',
        revision: 17,
        evidenceIds: ['evidence'],
      },
    ])
    const editor = setup()
    editor.openEditor('requirement')
    await settle()
    expect(presalesApi.statements).toHaveBeenCalledWith(project().workspaceId, project().id)
    editor.selectStatement('graph:90071992547409999')
    expect(editor.form.value).toMatchObject({
      statementId: '90071992547409999',
      graphId: 'graph',
      statementRevision: 17,
      evidenceIds: ['evidence'],
    })
    editor.selectStatement('missing')
    expect(editor.form.value).toMatchObject({
      statementId: '',
      graphId: '',
      statementRevision: '',
      evidenceIds: [],
    })
  })

  it('clears source-derived drafts synchronously but preserves the material repair editor', () => {
    const editor = setup()
    editor.openEditor('requirement', { title: 'Private draft' })
    editor.current.value = { ...project(), sourceAccessRestricted: true }
    expect(editor.editorOpen.value).toBe(false)
    expect(editor.form.value).toEqual({})
    editor.openEditor('material', { kbId: 'kb', graphId: 'graph' })
    editor.current.value = { ...project(), sourceAccessRestricted: true, version: 4 }
    expect(editor.editorOpen.value).toBe(true)
    expect(editor.form.value.kbId).toBe('kb')
    editor.current.value = undefined
    expect(editor.editorOpen.value).toBe(false)
    expect(editor.form.value).toEqual({})
  })

  it.each(['draft', 'busy', 'scope'] as const)(
    'rejects pending discard when %s changes',
    async (changed) => {
      const confirmation = deferred<unknown>(),
        editor = setup()
      editor.confirmDiscard.mockReturnValue(confirmation.promise)
      editor.openEditor('project')
      editor.form.value.name = 'First draft'
      const discarded = editor.discard()
      if (changed === 'draft') editor.form.value.name = 'Latest draft'
      if (changed === 'busy') editor.saving.value = true
      if (changed === 'scope') editor.scopeGeneration.value++
      confirmation.resolve('confirm')
      expect(await discarded).toBe(false)
      expect(editor.editorOpen.value).toBe(true)
      expect(editor.form.value.name).toBe(changed === 'draft' ? 'Latest draft' : 'First draft')
    },
  )

  it('retains a cancelled dirty draft and invokes close callback only for accepted discard', async () => {
    const editor = setup(),
      done = vi.fn()
    editor.openEditor('project')
    editor.form.value.name = 'Draft'
    editor.confirmDiscard.mockRejectedValueOnce('cancel')
    await editor.closeEditor(done)
    expect(done).not.toHaveBeenCalled()
    expect(editor.editorOpen.value).toBe(true)
    await editor.closeEditor(done)
    expect(done).toHaveBeenCalledTimes(1)
    expect(editor.editorOpen.value).toBe(false)
  })

  it('closes a clean draft without requesting confirmation', async () => {
    const editor = setup(),
      done = vi.fn()
    editor.openEditor('project')
    expect(editor.dirty.value).toBe(false)
    await editor.closeEditor(done)
    expect(editor.confirmDiscard).not.toHaveBeenCalled()
    expect(done).toHaveBeenCalledTimes(1)
  })

  it('aligns solution responses when the open baseline changes', async () => {
    const editor = setup()
    editor.current.value!.requirements = [
      { id: 'req-1', title: 'First' },
      { id: 'req-2', title: 'Second' },
    ]
    editor.current.value!.baselines = [
      { id: 'b1', references: [{ requirementId: 'req-1' }] },
      { id: 'b2', references: [{ requirementId: 'req-2' }] },
    ]
    editor.openEditor('solution')
    await nextTick()
    expect(editor.editableRequirements.value.map((item) => item.id)).toEqual(['req-2'])
    editor.form.value.baselineId = 'b1'
    await nextTick()
    expect(editor.form.value.requirementResponses).toEqual([
      { requirementId: 'req-1', status: 'UNHANDLED', reason: '' },
    ])
  })

  it('disposes capture validity and ignores late options after the owning scope stops', async () => {
    const options = deferred<PresalesSource[]>()
    vi.mocked(presalesApi.sources).mockReturnValue(options.promise)
    const editor = setup()
    editor.openEditor('material')
    const current = editor.captureSession()
    scope.stop()
    expect(current()).toBe(false)
    options.resolve([{ kbId: 'private', name: 'Late option' }])
    await settle()
    expect(editor.sourceOptions.value).toEqual([])
    editor.openEditor('project')
    expect(editor.editorKind.value).toBe('material')
  })

  it('protects unload while saving and removes its listener on scope disposal', () => {
    const editor = setup()
    editor.saving.value = true
    const before = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(before)
    expect(before.defaultPrevented).toBe(true)
    scope.stop()
    const after = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(after)
    expect(after.defaultPrevented).toBe(false)
  })
})
