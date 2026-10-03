import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref, type EffectScope } from 'vue'
import { presalesApi, type PresalesProject } from '../api/presalesApi'
import { usePresalesSourcePreview } from '../composables/usePresalesSourcePreview'

vi.mock('../api/presalesApi', () => ({ presalesApi: { file: vi.fn(), evidence: vi.fn() } }))
let scope: EffectScope
let createUrl: ReturnType<typeof vi.spyOn>, revokeUrl: ReturnType<typeof vi.spyOn>
const record = { id: 'record-1', graphId: 'graph-1', evidenceIds: ['evidence-1'] }
const presentation = {
  artifactId: 'artifact-1',
  skill: 'ppt-master-plus',
  skillVersion: '1.0',
  pageCount: 2,
  slides: [],
}
function setup() {
  const workspaceId = ref<string | null>('90071992547409999'),
    projectId = ref('90071992547409998')
  const project = ref<PresalesProject>({
    id: projectId.value,
    workspaceId: workspaceId.value!,
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
    solutions: [{ id: 'solution-1', presentation }],
    reviews: [],
    releases: [],
  })
  const error = ref('')
  scope = effectScope()
  const view = scope.run(() =>
    usePresalesSourcePreview({
      workspaceId: () => workspaceId.value,
      projectId: () => projectId.value,
      project,
      error,
    }),
  )!
  return { ...view, workspaceId, projectId, project, error }
}
beforeEach(() => {
  vi.resetAllMocks()
  createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:current-preview')
  revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => {
  scope?.stop()
  createUrl.mockRestore()
  revokeUrl.mockRestore()
})

describe('presales source view lifecycle', () => {
  it.each(['success', 'error'] as const)(
    'ignores evidence %s after its drawer closes',
    async (outcome) => {
      const state = setup()
      let finish!: () => void
      vi.mocked(presalesApi.evidence).mockImplementation(
        () =>
          new Promise((resolve, reject) => {
            finish = () =>
              outcome === 'success'
                ? resolve({ text: 'Late private source' })
                : reject(new Error('Late denied'))
          }),
      )
      const read = state.showEvidence(record)
      expect(state.evidenceOpen.value).toBe(true)
      state.evidenceOpen.value = false
      finish()
      await read
      expect(state.evidence.value).toBeUndefined()
      expect(state.evidenceOpen.value).toBe(false)
      expect(state.error.value).toBe('')
    },
  )

  it('revokes the opened image and ignores the next pending image when closed', async () => {
    const state = setup()
    vi.mocked(presalesApi.file).mockResolvedValueOnce(new Blob(['<svg>first</svg>']))
    await state.previewPresentation(presentation, 'first.svg')
    expect(presalesApi.file).toHaveBeenCalledWith(
      state.workspaceId.value,
      state.projectId.value,
      'solution-1',
      'first.svg',
      'draft',
    )
    expect(state.presentationPreview.value.open).toBe(true)
    let finish!: () => void
    vi.mocked(presalesApi.file).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = () => resolve(new Blob(['<svg>late</svg>']))
        }),
    )
    const read = state.previewPresentation(presentation, 'second.svg')
    state.presentationPreview.value.open = false
    expect(revokeUrl).toHaveBeenCalledTimes(1)
    expect(revokeUrl).toHaveBeenCalledWith('blob:current-preview')
    finish()
    await read
    expect(createUrl).toHaveBeenCalledTimes(1)
    expect(state.presentationPreview.value).toEqual({ open: false, url: '', title: '' })
  })

  it.each(['workspaceId', 'projectId'] as const)(
    'rejects an image after %s leaves and returns',
    async (key) => {
      const state = setup()
      let finish!: () => void
      vi.mocked(presalesApi.file).mockImplementation(
        () =>
          new Promise((resolve) => {
            finish = () => resolve(new Blob(['<svg>late</svg>']))
          }),
      )
      const read = state.previewPresentation(presentation, 'first.svg')
      const previous = state[key].value
      state[key].value = 'different'
      state[key].value = previous
      finish()
      await read
      expect(createUrl).not.toHaveBeenCalled()
    },
  )

  it.each(['success', 'error'] as const)(
    'ignores a pending image %s after disposal',
    async (outcome) => {
      const state = setup()
      let finish!: () => void
      vi.mocked(presalesApi.file).mockImplementation(
        () =>
          new Promise((resolve, reject) => {
            finish = () =>
              outcome === 'success'
                ? resolve(new Blob(['<svg>late</svg>']))
                : reject(new Error('Late image failure'))
          }),
      )
      const read = state.previewPresentation(presentation, 'first.svg')
      scope.stop()
      finish()
      await read
      expect(createUrl).not.toHaveBeenCalled()
      expect(state.error.value).toBe('')
    },
  )

  it('keeps a current evidence failure as unavailable metadata and releases URLs on disposal', async () => {
    const state = setup()
    vi.mocked(presalesApi.evidence).mockRejectedValue({
      response: { status: 403, data: { msg: 'Source denied' } },
    })
    await state.showEvidence(record)
    expect(state.evidence.value).toEqual({
      id: record.id,
      status: 'UNAVAILABLE',
      error: 'Source denied',
    })
    vi.mocked(presalesApi.file).mockResolvedValue(new Blob(['<svg>current</svg>']))
    await state.previewPresentation(presentation, 'first.svg')
    scope.stop()
    expect(revokeUrl).toHaveBeenCalledTimes(1)
    expect(state.evidence.value).toBeUndefined()
    expect(state.presentationPreview.value.url).toBe('')
  })

  it('does not read sources while access is restricted', async () => {
    const state = setup()
    state.project.value = { ...state.project.value, sourceAccessRestricted: true }
    await state.showEvidence(record)
    await state.previewPresentation(presentation, 'first.svg')
    expect(presalesApi.file).not.toHaveBeenCalled()
    expect(presalesApi.evidence).not.toHaveBeenCalled()
    expect(state.evidenceOpen.value).toBe(false)
  })
})
