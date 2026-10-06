import { afterEach, beforeEach, expect, expectTypeOf, it } from 'vitest'
import { http } from '@/api'
import {
  presalesApi,
  type PresalesCommandAction,
  type PresalesCommandRequest,
  type PresalesCommandIntent,
  type PresalesCommandPayloads,
} from '../api/presalesApi'

import type { EditorSubmission } from '../shared/editorSubmission'

const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})
type WithoutReceipt<T> = T extends unknown ? Omit<T, 'expectedVersion' | 'operationId'> : never
const intents: readonly WithoutReceipt<PresalesCommandRequest>[] = [
  {
    action: 'UPDATE_PROJECT',
    payload: { agentId: '', ownerId: '9223372036854775800', goal: '  exact  ' },
  },
  { action: 'ARCHIVE', payload: { ignoredHistoricalField: null } },
  { action: 'BIND_MATERIAL', payload: { kbId: 'kb/1', graphId: '', role: ' FUTURE ' } },
  {
    action: 'SAVE_REQUIREMENT',
    payload: {
      id: 'r/1',
      title: '  Original  ',
      scope: 'future',
      priority: 'future',
      statementRevision: '9223372036854775800',
    },
  },
  {
    action: 'SAVE_CLARIFICATION',
    payload: { question: 'Q', status: 'future', answerSourceId: 'source/1' },
  },
  { action: 'UNBIND_MATERIAL', payload: { id: 'material/1' } },
  { action: 'CANCEL_AI_TASK', payload: { taskId: 'task/1' } },
  {
    action: 'SAVE_AI_TASK',
    payload: {
      id: 'task/1',
      status: 'future',
      contextSnapshot: { raw: null },
      result: { modelOutput: ['untrusted'] },
    },
  },
  {
    action: 'SAVE_CONTEXT',
    payload: { title: 'Context', text: 'Exact', sourceRefs: ['source/1', 'source/1'] },
  },
  {
    action: 'SAVE_REVIEW',
    payload: {
      solutionId: 'solution/1',
      summary: 'Review',
      issues: [
        {
          severity: 'future',
          status: 'future',
          text: 'Exact',
          description: 'Actual editor field',
          extension: null,
        },
      ],
    },
  },
  { action: 'CREATE_RELEASE', payload: { solutionId: 'solution/1', purpose: 'candidate' } },
  { action: 'APPROVE_RELEASE', payload: { releaseId: 'release/1', reason: '  Approved  ' } },
  { action: 'PUBLISH_RELEASE', payload: { releaseId: 'release/1' } },
  {
    action: 'APPROVE_BASELINE',
    payload: { reason: '  Reviewed  ', references: [{ requirementId: 'r/1', extension: null }] },
  },
  {
    action: 'SAVE_FIT_GAP',
    payload: {
      requirementId: 'r/1',
      evidenceIds: ['source/1', 'source/1'],
      status: 'future',
      productVersion: 'Exact',
    },
  },
  {
    action: 'SAVE_SOLUTION',
    payload: {
      title: 'Solution',
      baselineId: 'b/1',
      sections: [{ title: 'Section', text: 'Exact', sourceRefs: ['source/1'] }],
      requirementResponses: [{ requirementId: 'r/1', status: 'future', evidenceIds: ['source/1'] }],
    },
  },
]
const commands: readonly PresalesCommandRequest[] = intents.map((intent) => ({
  ...intent,
  expectedVersion: 7,
  operationId: 'same-operation',
}))

it.each(commands)('keeps $action JSON bytes and server validation authority', async (command) => {
  const payload = { ...command.payload, extension: { nullable: null, values: ['same', 'same', 3] } }
  const body = { ...command, payload }
  const before = structuredClone(body)
  localStorage.setItem('mc-workspace-id', 'switched')
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe('captured')
    expect(config.url).toBe('/presales/projects/p%2F1/commands')
    expect(config.data).toBe(JSON.stringify(before))
    return {
      data: {
        data: {
          id: 'p/1',
          workspaceId: 'captured',
          version: 8,
          name: 'P',
          customer: 'C',
          ownerId: 'owner',
          status: 'ACTIVE',
          materials: [],
          requirements: [],
          clarifications: [],
          baselines: [],
          fitGaps: [],
          solutions: [],
          reviews: [],
          releases: [],
        },
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
  await presalesApi.command('captured', 'p/1', body)
  expect(body).toEqual(before)
})

type Bad<A extends PresalesCommandAction, P> = {
  action: A
  payload: P
  expectedVersion: number
  operationId: string
}
it('rejects wrong declared field types for their actual action at compile time', () => {
  expectTypeOf<
    Bad<'SAVE_REVIEW', { issues: [{ description: number }] }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<WithoutReceipt<PresalesCommandRequest>>().toEqualTypeOf<PresalesCommandIntent>()
  expectTypeOf<
    Extract<EditorSubmission, { kind: 'command'; action: 'SAVE_REVIEW' }>['payload']
  >().toEqualTypeOf<PresalesCommandPayloads['SAVE_REVIEW']>()
  expectTypeOf<
    Extract<EditorSubmission, { kind: 'command'; action: 'SAVE_SOLUTION' }>['payload']
  >().toEqualTypeOf<PresalesCommandPayloads['SAVE_SOLUTION']>()
  expectTypeOf<PresalesCommandPayloads['SAVE_AI_TASK']['result']>().toEqualTypeOf<unknown>()

  expectTypeOf<Bad<'UPDATE_PROJECT', { ownerId: number }>>().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<Bad<'BIND_MATERIAL', { kbId: number }>>().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_REQUIREMENT', { statementId: number }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_CLARIFICATION', { answerSourceId: number }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<Bad<'UNBIND_MATERIAL', { id: number }>>().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<Bad<'CANCEL_AI_TASK', { taskId: number }>>().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<Bad<'SAVE_AI_TASK', { id: number }>>().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_CONTEXT', { sourceRefs: number[] }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_REVIEW', { issues: [{ severity: number }] }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'CREATE_RELEASE', { solutionId: number }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'APPROVE_RELEASE', { releaseId: number }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'PUBLISH_RELEASE', { releaseId: number }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<Bad<'APPROVE_BASELINE', { reason: number }>>().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_FIT_GAP', { evidenceIds: number[] }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_SOLUTION', { sections: [{ text: number }] }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Bad<'SAVE_SOLUTION', { requirementResponses: [{ requirementId: number }] }>
  >().not.toExtend<PresalesCommandRequest>()
  expectTypeOf<
    Extract<PresalesCommandRequest, { action: 'SAVE_REQUIREMENT' }>['payload']['title']
  >().toEqualTypeOf<string | undefined>()
})
