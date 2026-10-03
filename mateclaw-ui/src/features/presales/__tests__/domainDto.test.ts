import { afterEach, expect, expectTypeOf, it } from 'vitest'
import { http } from '@/api'
import {
  presalesApi,
  type PresalesProject,
  type PresalesRequirement,
  type PresalesClarification,
  type PresalesSolutionRevision,
  type PresalesGenerationTask,
  type PresalesArtifact,
  type PresalesHandoff,
} from '../api/presalesApi'

const adapter = http.defaults.adapter
afterEach(() => {
  http.defaults.adapter = adapter
})
const ws = '9223372036854775801',
  id = 'p/9223372036854775800'
function project() {
  return {
    id,
    workspaceId: ws,
    version: 1,
    name: 'P',
    customer: 'C',
    ownerId: 'owner',
    status: 'ACTIVE',
    materials: [],
    requirements: [{ id: 'r', title: 'Raw', scope: ' FUTURE ', authorId: 'author' }],
    clarifications: [{ id: 'c', question: 'Q', answerSourceId: 'source', status: 'future' }],
    baselines: [
      {
        id: 'b',
        approvedBy: 'reviewer',
        projectVersion: 1,
        references: [
          {
            requirementId: 'r',
            requirementVersion: 2,
            sources: [{ sourceRef: 'kb:k:item:i', extension: null }],
            assertion: { raw: null },
          },
        ],
      },
    ],
    fitGaps: [{ id: 'fit', requirementId: 'r', status: 'future' }],
    solutions: [
      {
        id: 's',
        previousId: 'old',
        authorId: 'author',
        baselineId: 'b',
        baselineVersion: 1,
        provisional: false,
        fitGapRefs: ['fit', 'fit'],
        sections: [{ title: 'T', text: '  Exact  ', requirementRefs: ['r'] }],
        coverage: { applicable: false, handledIn: 0, totalIn: 0, percentage: null },
      },
    ],
    reviews: [],
    releases: [
      {
        id: 'release',
        files: [{ filename: 'solution.pdf', sha256: 'raw-digest', size: 3 }],
        handoffSnapshot: { untouched: null },
      },
    ],
    tasks: [
      {
        id: 'task',
        status: 'SUCCEEDED',
        queuedAt: 'start',
        finishedAt: 'finish',
        rejectedOutput: null,
        result: { items: [{ title: 'Draft', extension: null }], assumptions: null },
      },
    ],
  }
}
function handoff() {
  const p = project()
  return {
    schemaVersion: 1,
    engagementId: id,
    caseRef: id,
    workspaceId: ws,
    baseline: p.baselines[0],
    solution: p.solutions[0],
    release: p.releases[0],
    fitGaps: p.fitGaps,
    clarifications: p.clarifications,
    risksAndUnknowns: [{ requirementId: 'r', status: 'UNHANDLED', extension: null }],
    customerConfirmationStatus: 'UNCONFIRMED',
    accessPolicy: 'WORKSPACE_REAUTHORIZE_ON_READ',
    extension: { nullable: null },
  }
}
function respond(value: unknown) {
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe(ws)
    return { data: { data: value }, status: 200, statusText: 'OK', headers: {}, config }
  }
}
it('preserves actual domain fields, nullable percentage, sparse historical items and opaque snapshots', async () => {
  const value = project(),
    before = JSON.stringify(value)
  respond(value)
  expect(await presalesApi.get(ws, id)).toBe(value)
  expect(JSON.stringify(value)).toBe(before)
})
it('preserves nullable semantic fact metadata only inside baseline references', async () => {
  const value = handoff()
  const reference = {
    requirementId: 'r',
    ontologyRevisionId: null,
    evidenceIds: ['e', null],
    assertion: null,
  }
  const baseline = { id: 'b', references: [reference] }
  const detail = { ...project(), baselines: [baseline] }
  respond(detail)
  expect(await presalesApi.get(ws, id)).toBe(detail)
  const frozen = { ...value, baseline, sourceRefs: [reference] }
  respond(frozen)
  expect(await presalesApi.handoff(ws, id)).toBe(frozen)
})
it('does not widen ordinary records when a shared object also appears as a baseline reference', async () => {
  const shared = { id: 'r', ontologyRevisionId: null }
  respond({ ...project(), materials: [shared], baselines: [{ id: 'b', references: [shared] }] })
  await expect(presalesApi.get(ws, id)).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
})
const resultBranches = [
  'items',
  'capabilityMaps',
  'cases',
  'solution',
  'solutionDraft',
  'review',
  'reviewDraft',
] as const
it.each(resultBranches)(
  'admits the raw %s model branch without upgrading opaque envelope values',
  async (branch) => {
    const draft = { title: 'Untrusted', extension: { nullable: null } }
    const result = {
      [branch]: ['items', 'capabilityMaps', 'cases'].includes(branch) ? [draft] : draft,
      schemaVersion: '1',
      needsHumanReview: 'true',
      unknowns: null,
      assumptions: null,
    }
    const value = {
      ...project(),
      tasks: [{ id: 't', status: 'future', result, rejectedOutput: null }],
    }
    const before = JSON.stringify(value)
    respond(value)
    expect(await presalesApi.get(ws, id)).toBe(value)
    expect(JSON.stringify(value)).toBe(before)
  },
)
it.each(resultBranches)(
  'rejects malformed declared fields inside the %s model branch',
  async (branch) => {
    const draft = { authorId: 3 }
    const result = {
      [branch]: ['items', 'capabilityMaps', 'cases'].includes(branch) ? [draft] : draft,
    }
    respond({ ...project(), tasks: [{ id: 't', status: 'future', result }] })
    await expect(presalesApi.get(ws, id)).rejects.toMatchObject({
      code: 'PRESALES_RESPONSE_INVALID',
    })
  },
)
const invalidProject = [
  { requirements: [{ id: 'r', authorId: 3 }] },
  { solutions: [{ id: 's', previousId: 3 }] },
  { solutions: [{ id: 's', baselineVersion: '1' }] },
  { solutions: [{ id: 's', provisional: null }] },
  { solutions: [{ id: 's', fitGapRefs: [3] }] },
  { baselines: [{ id: 'b', references: [{ requirementVersion: '1' }] }] },
  { baselines: [{ id: 'b', projectVersion: 1.5 }] },
  { baselines: [{ id: 'b', approvedBy: 3 }] },
  { releases: [{ id: 'release', reviewId: 3 }] },
  { releases: [{ id: 'release', templateVersion: 3 }] },
  { releases: [{ id: 'release', files: [{ filename: 'x', size: -1 }] }] },
  { releases: [{ id: 'release', files: [{ filename: 'x', sha256: 3 }] }] },
  { tasks: [{ id: 't', status: 'DONE', finishedAt: 3 }] },
  { tasks: [{ id: 't', status: 'DONE', queuedAt: 3 }] },
  ...['3', Number.POSITIVE_INFINITY, -1, 101].map((percentage) => ({
    solutions: [{ id: 's', coverage: { applicable: true, handledIn: 1, totalIn: 2, percentage } }],
  })),
]
it.each(invalidProject)('rejects malformed declared domain fields %j', async (patch) => {
  respond({ ...project(), ...patch })
  await expect(presalesApi.get(ws, id)).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
})
it('returns the exact handoff object and JSON after scoped admission', async () => {
  const value = handoff(),
    before = JSON.stringify(value)
  respond(value)
  expect(await presalesApi.handoff(ws, id)).toBe(value)
  expect(JSON.stringify(value)).toBe(before)
})
it('preserves frozen sourceRefs that combine baseline objects and raw clarification references', async () => {
  const value = {
    ...handoff(),
    releaseId: 'release',
    historicalClarificationsAvailable: true,
    materials: [],
    sourceRefs: [
      { requirementId: 'r', assertion: null },
      'kb:k:item:i',
      null,
      { historical: true },
    ],
  }
  const before = JSON.stringify(value)
  respond(value)
  expect(await presalesApi.handoff(ws, id)).toBe(value)
  expect(JSON.stringify(value)).toBe(before)
})
const invalidHandoff = [
  { sourceRefs: 'source' },
  { schemaVersion: '1' },
  { schemaVersion: 0 },
  { schemaVersion: 2 },
  { workspaceId: 'other' },
  { engagementId: 'other' },
  { caseRef: 'other' },
  { baseline: null },
  { baseline: { id: 3 } },
  { solution: [] },
  { release: {} },
  { fitGaps: null },
  { clarifications: [{ id: 3 }] },
  { risksAndUnknowns: [null] },
  { customerConfirmationStatus: 3 },
  { accessPolicy: null },
  { historicalClarificationsAvailable: 'true' },
  { releaseId: 3 },
]
it.each(invalidHandoff)('rejects malformed or cross-project handoff %j', async (patch) => {
  respond({ ...handoff(), ...patch })
  await expect(presalesApi.handoff(ws, id)).rejects.toMatchObject({
    code: 'PRESALES_RESPONSE_INVALID',
  })
})
it('declares domain revisions, manifests and scoped handoff fields at compile time', () => {
  expectTypeOf<PresalesProject['requirements'][number]>().toEqualTypeOf<PresalesRequirement>()
  expectTypeOf<PresalesProject['clarifications'][number]>().toEqualTypeOf<PresalesClarification>()
  expectTypeOf<PresalesProject['solutions'][number]>().toEqualTypeOf<PresalesSolutionRevision>()
  expectTypeOf<
    NonNullable<PresalesProject['tasks']>[number]
  >().toEqualTypeOf<PresalesGenerationTask>()
  expectTypeOf<
    NonNullable<PresalesProject['releases'][number]['files']>[number]
  >().toEqualTypeOf<PresalesArtifact>()
  expectTypeOf<Awaited<ReturnType<typeof presalesApi.handoff>>>().toEqualTypeOf<PresalesHandoff>()
  expectTypeOf<NonNullable<PresalesHandoff['sourceRefs']>[number]>().toEqualTypeOf<unknown>()
  expectTypeOf<PresalesProject['requirements'][number]['authorId']>().toEqualTypeOf<
    string | undefined
  >()
  expectTypeOf<PresalesProject['solutions'][number]['baselineVersion']>().toEqualTypeOf<
    number | undefined
  >()
  expectTypeOf<PresalesProject['solutions'][number]['provisional']>().toEqualTypeOf<
    boolean | undefined
  >()
  expectTypeOf<
    NonNullable<PresalesProject['releases'][number]['files']>[number]['sha256']
  >().toEqualTypeOf<string | undefined>()
  expectTypeOf<
    NonNullable<PresalesProject['solutions'][number]['coverage']>['percentage']
  >().toEqualTypeOf<number | null | undefined>()
  expectTypeOf<Awaited<ReturnType<typeof presalesApi.handoff>>>().toExtend<{
    workspaceId: string
    engagementId: string
    caseRef: string
    schemaVersion: number
  }>()
})
