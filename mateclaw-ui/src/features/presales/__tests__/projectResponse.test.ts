import { afterEach, expect, it } from 'vitest'
import { http } from '@/api'
import { presalesApi } from '../api/presalesApi'
import { decodeProject } from '../api/presalesResponse'

const originalAdapter = http.defaults.adapter
afterEach(() => {
  http.defaults.adapter = originalAdapter
})
const ws = '9223372036854775801'
const id = 'p/9223372036854775800'
const collections = [
  'materials',
  'requirements',
  'clarifications',
  'baselines',
  'fitGaps',
  'cases',
  'solutions',
  'reviews',
  'reviewDrafts',
  'releases',
  'tasks',
  'contextCards',
]
const summary = {
  id,
  workspaceId: ws,
  version: 1,
  name: 'Factory',
  customer: 'Customer',
  ownerId: '9223372036854775802',
  status: 'HISTORICAL_STATUS',
}
function project(): Record<string, unknown> {
  return { ...summary, ...Object.fromEntries(collections.map((key) => [key, []])) }
}
function respond(value: unknown) {
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe(ws)
    return { data: { data: value }, status: 200, statusText: 'OK', headers: {}, config }
  }
}
const invalid = [
  { id: 922337 },
  { workspaceId: 'other' },
  { id: 'other' },
  { version: 1.5 },
  { version: Number.MAX_SAFE_INTEGER + 1 },
  { materials: null },
  { requirements: [{}] },
  { tasks: [{ id: 't', status: 3 }] },
  { solutions: [{ id: 's', sections: [{ title: 7 }] }] },
  { solutions: [{ id: 's', presentation: { artifactId: 'a', skill: 'S7', pageCount: 1 } }] },
  { sourceAccessRestricted: 'false' },
]
it.each(invalid)('rejects invalid project admission %j', async (patch) => {
  respond({ ...project(), ...patch })
  await expect(presalesApi.get(ws, id)).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
})
it('preserves raw extensions and nested records without fabricated identifiers', async () => {
  const detail = {
    ...project(),
    context: null,
    extension: { nullable: null },
    solutions: [
      {
        id: 's',
        sections: [{ title: 'Untitled', extension: null }],
        handoffSnapshot: { sourceRefs: [{ kbId: 'k' }] },
      },
    ],
  }
  const before = JSON.stringify(detail)
  respond(detail)
  expect(JSON.stringify(await presalesApi.get(ws, id))).toBe(before)
})
it.each(['create', 'update', 'command', 'generate', 'cancelTask'] as const)(
  'guards %s project responses',
  async (method) => {
    respond({ ...project(), workspaceId: 'other' })
    const receipt = { expectedVersion: 1, operationId: 'same-receipt' }
    const calls = {
      create: () => presalesApi.create(ws, { expectedVersion: 0, operationId: 'same-receipt' }),
      update: () => presalesApi.update(ws, id, receipt),
      command: () => presalesApi.command(ws, id, { ...receipt, action: 'ARCHIVE', payload: {} }),
      generate: () =>
        presalesApi.generate(ws, id, { ...receipt, skill: 'S1', taskGoal: 'Analyze' }),
      cancelTask: () => presalesApi.cancelTask(ws, id, 't', { operationId: 'same-receipt' }),
    }
    await expect(calls[method]()).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
  },
)
it.each([0, '0', '42'])('retains wire total %j', async (total) => {
  respond({ items: [summary], total, page: 1, pageSize: 100 })
  expect((await presalesApi.list(ws, {})).total).toBe(total)
})
it.each([-1, '01', '9007199254740992', Number.MAX_SAFE_INTEGER + 1])(
  'rejects unsafe total %j',
  async (total) => {
    respond({ items: [summary], total, page: 1, pageSize: 100 })
    await expect(presalesApi.list(ws, {})).rejects.toMatchObject({
      code: 'PRESALES_RESPONSE_INVALID',
    })
  },
)
it('rejects collection-bearing summaries', async () => {
  respond({ items: [project()], total: 1, page: 1, pageSize: 100 })
  await expect(presalesApi.list(ws, {})).rejects.toMatchObject({
    code: 'PRESALES_RESPONSE_INVALID',
  })
})
it('accepts exact repair projection with UNKNOWN role', async () => {
  const repair = {
    ...project(),
    sourceAccessRestricted: true,
    repairBindings: [{ id: 'm', role: 'UNKNOWN' }],
  }
  respond(repair)
  expect(await presalesApi.repairContext(ws, id)).toEqual(repair)
  expect(await presalesApi.get(ws, id)).toEqual(repair)
})
it.each([
  { context: { secret: 'LEAK_MARKER' } },
  { tasks: [{ id: 't', status: 'DONE' }] },
  { repairBindings: [{ id: 3, role: 'UNKNOWN' }] },
  { sourceAccessRestricted: false },
])('rejects invalid repair projection %j', async (patch) => {
  respond({ ...project(), sourceAccessRestricted: true, repairBindings: [], ...patch })
  await expect(presalesApi.repairContext(ws, id)).rejects.toMatchObject({
    code: 'PRESALES_RESPONSE_INVALID',
    message: 'Unable to read project data. Reload and try again.',
  })
})

it('preserves object identity and opaque nullable extensions without a depth cutoff', () => {
  let section: Record<string, unknown> = {
    title: 'Last',
    handoffSnapshot: { sourceRefs: [{ kbId: 'k' }] },
  }
  for (let depth = 0; depth < 2000; depth++) section = { sections: [section] }
  const detail = { ...project(), solutions: [{ id: 's', ...section }], context: null }
  expect(decodeProject(detail, ws, id)).toBe(detail)
})
it('accepts a newly created project ID while enforcing the workspace', async () => {
  const detail = { ...project(), id: 'new-project' }
  respond(detail)
  expect((await presalesApi.create(ws, { expectedVersion: 0, operationId: 'create' })).id).toBe(
    'new-project',
  )
})
it.each(['update', 'command', 'generate', 'cancelTask'] as const)(
  'rejects wrong project identity from %s',
  async (method) => {
    respond({ ...project(), id: 'other-project' })
    const receipt = { expectedVersion: 1, operationId: 'same-receipt' }
    const calls = {
      update: () => presalesApi.update(ws, id, receipt),
      command: () => presalesApi.command(ws, id, { ...receipt, action: 'ARCHIVE', payload: {} }),
      generate: () =>
        presalesApi.generate(ws, id, { ...receipt, skill: 'S1', taskGoal: 'Analyze' }),
      cancelTask: () => presalesApi.cancelTask(ws, id, 't', { operationId: 'same-receipt' }),
    }
    await expect(calls[method]()).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
  },
)
it('keeps absent optional contextCards and valid manifests/coverage/model records', async () => {
  const detail = project()
  delete detail.contextCards
  detail.solutions = [
    {
      id: 's',
      presentation: {
        artifactId: 'a',
        skill: 'S7',
        skillVersion: '1',
        pageCount: 1,
        slides: [{ filename: 'slide.svg', digest: 'preserved' }],
      },
      coverage: {
        applicable: true,
        handledIn: 1,
        totalIn: 1,
        responses: [{ requirementId: 'r', status: 'ANSWERED' }],
      },
      contextSnapshot: { truncated: false, materials: [{ sourceRefs: [{ kbId: 'k' }] }] },
      result: { items: [{ text: 'Draft' }], solution: { title: 'Draft' }, unknowns: null },
    },
  ]
  respond(detail)
  expect(await presalesApi.get(ws, id)).toEqual(detail)
})
