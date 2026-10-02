import { afterEach, beforeEach, describe, expect, expectTypeOf, it } from 'vitest'
import { AxiosError } from 'axios'
import { http } from '@/api'
import {
  presalesApi,
  type PresalesProjectCreate,
  type PresalesProjectWrite,
  type PresalesCommandRequest,
  type PresalesCommandAction,
  type PresalesGenerateRequest,
  type PresalesCancelRequest,
  type PresalesListQuery,
  type PresalesSkill,
} from '../api/presalesApi'

const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})
const workspace = '9223372036854775801'
const projectId = 'p/9223372036854775800'
const encodedProject = 'p%2F9223372036854775800'
const metadata = {
  name: 'Factory',
  customer: 'Customer',
  ownerId: '9223372036854775802',
  agentId: '',
  industry: 'Manufacturing',
  goal: 'Preserved goal',
}
const result = { id: '9223372036854775800', version: 8 }
const actions = [
  'UPDATE_PROJECT',
  'ARCHIVE',
  'BIND_MATERIAL',
  'SAVE_REQUIREMENT',
  'SAVE_CLARIFICATION',
  'UNBIND_MATERIAL',
  'CANCEL_AI_TASK',
  'SAVE_AI_TASK',
  'SAVE_CONTEXT',
  'SAVE_REVIEW',
  'CREATE_RELEASE',
  'APPROVE_RELEASE',
  'PUBLISH_RELEASE',
  'APPROVE_BASELINE',
  'SAVE_FIT_GAP',
  'SAVE_SOLUTION',
] as const

function expectRequest(method: string, url: string, body: object) {
  const bytes = JSON.stringify(body)
  localStorage.setItem('mc-workspace-id', 'different')
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe(workspace)
    expect(config.url).toBe(url)
    expect(config.method).toBe(method)
    expect(config.data).toBe(bytes)
    return { data: { data: result }, status: 200, statusText: 'OK', headers: {}, config }
  }
}

describe('presales mutation wire boundary', () => {
  it('retains project creation fields, explicit zero CAS and operation key', async () => {
    const body = { ...metadata, expectedVersion: 0 as const, operationId: 'create-operation' }
    expectRequest('post', '/presales/projects', body)
    expect(await presalesApi.create(workspace, body)).toEqual(result)
  })
  it('retains partial project updates without filling absent metadata', async () => {
    const body = { agentId: '', expectedVersion: 7, operationId: 'update-operation' }
    expectRequest('patch', `/presales/projects/${encodedProject}`, body)
    expect(await presalesApi.update(workspace, projectId, body)).toEqual(result)
    expect(body).not.toHaveProperty('name')
  })
  it.each(actions)('retains %s command raw payload, receipt and version', async (action) => {
    const body = {
      action,
      payload: {
        id: '9223372036854775803',
        title: 'Original title',
        extension: { nullable: null, values: ['preserved', 3] },
      },
      expectedVersion: 7,
      operationId: 'same-operation',
    }
    const before = structuredClone(body)
    expectRequest('post', `/presales/projects/${encodedProject}/commands`, body)
    expect(await presalesApi.command(workspace, projectId, body)).toEqual(result)
    expect(body).toEqual(before)
  })
  it('retains generation inputs and captured project CAS', async () => {
    const body = {
      skill: 'S8' as const,
      taskGoal: 'Prepare handoff',
      expectedVersion: 7,
      operationId: 'generation-operation',
    }
    expectRequest('post', `/presales/projects/${encodedProject}/generate`, body)
    expect(await presalesApi.generate(workspace, projectId, body)).toEqual(result)
  })
  it('cancels exact encoded task with its operation key and no project CAS field', async () => {
    const body = { operationId: 'cancel-operation' }
    expectRequest('post', `/presales/projects/${encodedProject}/tasks/t%2F1/cancel`, body)
    expect(await presalesApi.cancelTask(workspace, projectId, 't/1', body)).toEqual(result)
  })
  it('preserves 409 code/message and leaves caller command input intact', async () => {
    const body = {
      action: 'SAVE_REQUIREMENT' as const,
      payload: { title: 'Keep edited input' },
      expectedVersion: 7,
      operationId: 'uncertain-operation',
    }
    const before = structuredClone(body)
    http.defaults.adapter = async (config) => {
      throw new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
        data: { code: 409, msg: 'Reload before saving', data: { code: 'VERSION_CONFLICT' } },
        status: 409,
        statusText: 'Conflict',
        headers: {},
        config,
      })
    }
    await expect(presalesApi.command(workspace, projectId, body)).rejects.toMatchObject({
      message: 'Reload before saving',
      response: {
        status: 409,
        data: {
          data: { code: 'VERSION_CONFLICT' },
        },
      },
    })
    expect(body).toEqual(before)
  })
  it('preserves all existing list filters, pagination and caller signal', async () => {
    const signal = new AbortController().signal
    const params = {
      q: 'Factory',
      status: 'ACTIVE',
      ownerId: '9223372036854775802',
      stage: 'SOLUTION',
      page: 2,
      pageSize: 20,
    }
    http.defaults.adapter = async (config) => {
      expect(config.headers.get('X-Workspace-Id')).toBe(workspace)
      expect(config.url).toBe('/presales/projects')
      expect(config.params).toEqual(params)
      expect(config.signal).toBe(signal)
      return {
        data: { data: { items: [], total: 0, page: 2, pageSize: 20 } },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    expect(await presalesApi.list(workspace, params, signal)).toMatchObject({ items: [], page: 2 })
  })
})

it('requires the declared mutation and query contracts at every API boundary', () => {
  expectTypeOf<Parameters<typeof presalesApi.create>[1]>().toEqualTypeOf<PresalesProjectCreate>()
  expectTypeOf<Parameters<typeof presalesApi.update>[2]>().toEqualTypeOf<PresalesProjectWrite>()
  expectTypeOf<Parameters<typeof presalesApi.command>[2]>().toEqualTypeOf<PresalesCommandRequest>()
  expectTypeOf<
    Parameters<typeof presalesApi.generate>[2]
  >().toEqualTypeOf<PresalesGenerateRequest>()
  expectTypeOf<
    Parameters<typeof presalesApi.cancelTask>[3]
  >().toEqualTypeOf<PresalesCancelRequest>()
  expectTypeOf<Parameters<typeof presalesApi.list>[1]>().toEqualTypeOf<PresalesListQuery>()
  expectTypeOf<Record<string, never>>().not.toExtend<PresalesProjectWrite>()
  expectTypeOf<{ expectedVersion: number }>().not.toExtend<PresalesProjectWrite>()
  expectTypeOf<{ operationId: string }>().not.toExtend<PresalesProjectWrite>()
  expectTypeOf<{
    ownerId: number
    expectedVersion: number
    operationId: string
  }>().not.toExtend<PresalesProjectWrite>()
  expectTypeOf<{ expectedVersion: 7; operationId: string }>().not.toExtend<PresalesProjectCreate>()
  expectTypeOf<keyof PresalesCancelRequest>().toEqualTypeOf<'operationId'>()
  expectTypeOf<(typeof actions)[number]>().toEqualTypeOf<PresalesCommandAction>()
  expectTypeOf<'unknown' | 'save_requirement'>().not.toExtend<PresalesCommandAction>()
  expectTypeOf<'S9' | 's1'>().not.toExtend<PresalesSkill>()
  expectTypeOf<{ q: number }>().not.toExtend<PresalesListQuery>()
  expectTypeOf<keyof PresalesListQuery>().toEqualTypeOf<
    'q' | 'status' | 'ownerId' | 'stage' | 'page' | 'pageSize'
  >()
})
