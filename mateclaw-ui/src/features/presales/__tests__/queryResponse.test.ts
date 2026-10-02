import { afterEach, beforeEach, expect, expectTypeOf, it } from 'vitest'
import { http } from '@/api'
import {
  presalesApi,
  type PresalesTrustedStatement,
  type PresalesSource,
  type PresalesRecord,
} from '../api/presalesApi'
import {
  decodeMembers,
  decodeSources,
  decodeStatements,
  decodeEmployees,
  decodeCapabilities,
} from '../api/presalesResponse'
import { AxiosError } from 'axios'

const originalAdapter = http.defaults.adapter
const ws = '9223372036854775801'
const projectId = 'p/9223372036854775800'
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})
const member = {
  id: '9223372036854775802',
  workspaceId: ws,
  userId: '9223372036854775803',
  role: 'HISTORICAL_ROLE',
  username: null,
  nickname: null,
}
const source = { kbId: '9223372036854775804', name: null, graphId: 'g', ontologyRevisionId: null }
const statement = {
  id: '9223372036854775805',
  revision: 3,
  graphId: 'g',
  ontologyRevisionId: null,
  label: 'Trusted fact',
  evidenceIds: ['e2', null, 'e1', 'e2'],
}
const employee = { id: '9223372036854775806', name: 'Employee', enabled: true, available: false }
const capabilities = { enabled: true, semanticEnabled: false, canWrite: false, canApprove: false }
function respond(value: unknown, url?: string, signal?: AbortSignal) {
  localStorage.setItem('mc-workspace-id', 'other')
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe(ws)
    if (url) expect(config.url).toBe(url)
    if (signal) expect(config.signal).toBe(signal)
    return { data: { data: value }, status: 200, statusText: 'OK', headers: {}, config }
  }
}
it('preserves nullable members, raw roles, IDs, extensions, scoped path and signal', async () => {
  const value = [{ ...member, extension: { nullable: null } }]
  const signal = new AbortController().signal
  respond(value, `/workspaces/${ws}/members`, signal)
  expect(await presalesApi.members(ws, signal)).toEqual(value)
})
it('keeps plain source fields absent and graph source nulls present', async () => {
  const value = [{ kbId: 'plain', name: null }, source]
  respond(value, '/presales/sources')
  const result = await presalesApi.sources(ws)
  expect(result).toEqual(value)
  expect(result[0]).not.toHaveProperty('graphId')
})
it('preserves null evidence list, null members, ordering and duplicate evidence IDs', async () => {
  const value = [statement, { ...statement, id: 'second', evidenceIds: null }]
  respond(value, '/presales/projects/p%2F9223372036854775800/statements')
  expect(await presalesApi.statements(ws, projectId)).toEqual(value)
})
it('does not promote false employee availability or mutate capability flags', async () => {
  respond([employee], '/presales/employees')
  expect(await presalesApi.employees(ws)).toEqual([employee])
  respond(capabilities, '/presales/capabilities')
  expect(await presalesApi.capabilities(ws)).toEqual(capabilities)
})
it.each(['members', 'sources', 'statements', 'employees'] as const)(
  'accepts empty %s lists',
  async (kind) => {
    respond([])
    const result =
      kind === 'statements'
        ? await presalesApi.statements(ws, projectId)
        : await presalesApi[kind](ws)
    expect(result).toEqual([])
  },
)
it.each([
  { userId: 3 },
  { id: 3 },
  { workspaceId: 'other' },
  { role: null },
  { nickname: 3 },
  { workspaceId: undefined },
])('rejects malformed member %j', async (patch) => {
  respond([{ ...member, ...patch }])
  await expect(presalesApi.members(ws)).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
})
it.each([
  { kbId: 3 },
  { name: 3 },
  { graphId: 3 },
  { ontologyRevisionId: 3 },
  { graphId: undefined },
])('rejects malformed source %j', async (patch) => {
  respond([{ ...source, ...patch }])
  await expect(presalesApi.sources(ws)).rejects.toMatchObject({ code: 'PRESALES_RESPONSE_INVALID' })
})
it.each([
  { id: 3 },
  { revision: 1.5 },
  { revision: '3' },
  { evidenceIds: [3] },
  { evidenceIds: undefined },
  { graphId: null },
  { label: 3 },
])('rejects malformed trusted fact %j', async (patch) => {
  respond([{ ...statement, ...patch }])
  await expect(presalesApi.statements(ws, projectId)).rejects.toMatchObject({
    code: 'PRESALES_RESPONSE_INVALID',
  })
})
it.each([{ id: 3 }, { name: null }, { enabled: 'true' }, { available: 'false' }])(
  'rejects malformed employee %j',
  async (patch) => {
    respond([{ ...employee, ...patch }])
    await expect(presalesApi.employees(ws)).rejects.toMatchObject({
      code: 'PRESALES_RESPONSE_INVALID',
    })
  },
)
it.each([
  { enabled: 'true' },
  { semanticEnabled: null },
  { canWrite: 1 },
  { canApprove: undefined },
  { modelConfigured: 'false' },
])('rejects malformed capabilities %j', async (patch) => {
  respond({ ...capabilities, ...patch })
  await expect(presalesApi.capabilities(ws)).rejects.toMatchObject({
    code: 'PRESALES_RESPONSE_INVALID',
  })
})

it('keeps query arrays/objects identical and nullable DTOs distinct from strict records', () => {
  const members = [member],
    sources = [source],
    statements = [statement],
    employees = [employee]
  expect(decodeMembers(members, ws)).toBe(members)
  expect(decodeSources(sources)).toBe(sources)
  expect(decodeStatements(statements)).toBe(statements)
  expect(decodeEmployees(employees)).toBe(employees)
  expect(decodeCapabilities(capabilities)).toBe(capabilities)
  expectTypeOf<PresalesSource['name']>().toEqualTypeOf<string | null>()
  expectTypeOf<PresalesTrustedStatement['evidenceIds']>().toEqualTypeOf<(string | null)[] | null>()
  expectTypeOf<PresalesTrustedStatement>().not.toExtend<PresalesRecord>()
})
it.each(['members', 'sources', 'statements', 'employees', 'capabilities'] as const)(
  'rejects wrong root shapes for %s',
  async (kind) => {
    respond(kind === 'capabilities' ? [] : { secret: 'DO_NOT_ECHO' })
    const result =
      kind === 'statements' ? presalesApi.statements(ws, projectId) : presalesApi[kind](ws)
    await expect(result).rejects.toMatchObject({
      code: 'PRESALES_RESPONSE_INVALID',
      message: 'Unable to read project data. Reload and try again.',
    })
  },
)
it('preserves scoped employee/capability signals and propagates server denial unchanged', async () => {
  const signal = new AbortController().signal
  respond([employee], '/presales/employees', signal)
  expect(await presalesApi.employees(ws, signal)).toEqual([employee])
  respond(capabilities, '/presales/capabilities', signal)
  expect(await presalesApi.capabilities(ws, signal)).toEqual(capabilities)
  const denied = new AxiosError('Denied', 'ERR_BAD_REQUEST')
  http.defaults.adapter = async (config) => {
    denied.response = {
      status: 403,
      statusText: 'Forbidden',
      data: { code: 'SOURCE_DENIED' },
      headers: {},
      config,
    }
    throw denied
  }
  try {
    await presalesApi.statements(ws, projectId)
    expect.fail('Server denial must reject')
  } catch (error) {
    expect(error).toBeInstanceOf(Error)
    expect(error).toMatchObject({
      message: 'Denied',
      response: { status: 403, data: { code: 'SOURCE_DENIED' } },
    })
    expect(error).toHaveProperty('response', denied.response)
  }
})
