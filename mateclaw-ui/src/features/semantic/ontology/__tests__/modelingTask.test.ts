import { buildChatRouteQuery } from '@/utils/chatRouteHydration'
import { expect, it } from 'vitest'
import { http } from '@/api'
import {
  modelingTaskApi,
  modelingTaskPrompt,
  proposalDetails,
  proposalLabel,
} from '../../api/modelingTaskApi'
it('keeps task and batch IDs, exact answers and operation ID scoped for retry', async () => {
  const previous = http.defaults.adapter
  const payload = {
    operationId: 'same-operation',
    decision: 'ACCEPT' as const,
    answers: { '设备属于哪个工厂？': '一厂' },
  }
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe('9223372036854775800')
    expect(config.url).toBe(
      '/semantic/modeling-tasks/task%2F1/proposals/batch%2F1/decision',
    )
    expect(JSON.parse(config.data)).toEqual(payload)
    return {
      data: { code: 200, data: { id: 'task/1' } },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
  try {
    expect(
      await modelingTaskApi.decide(
        '9223372036854775800',
        'task/1',
        'batch/1',
        payload,
      ),
    ).toEqual({ id: 'task/1' })
    await modelingTaskApi.decide(
      '9223372036854775800',
      'task/1',
      'batch/1',
      payload,
    )
  } finally {
    http.defaults.adapter = previous
  }
})
it('shows business names for local references and current model terms', () => {
  const changes: import('../../api/modelingTaskApi').ModelingChange[] = [
    { kind: 'CREATE_TERM', clientId: 'equipment', name: '设备' },
    {
      kind: 'CREATE_TERM',
      name: '安装传感器',
      domainId: '$equipment',
      rangeId: 'urn:sensor',
    },
  ]
  expect(proposalLabel(changes[1]!)).toBe('新增 · 安装传感器')
  expect(
    proposalDetails(changes[1]!, changes, { 'urn:sensor': '传感器' }),
  ).toBe('所属对象：设备 · 关联对象：传感器')
  expect(
    proposalDetails(
      { kind: 'REPLACE_DEFINITION', targetId: 'urn:missing', field: 'NAME' },
      [],
    ),
  ).not.toContain('urn:')
})
it('resumes the same durable task and leaves approval to the user', () => {
  expect(modelingTaskPrompt('task-42')).toContain('task-42')
  expect(modelingTaskPrompt('task-42')).toContain('不要代替用户确认建议或发布')
})

it('keeps task review deep link when chat obtains its conversation ID, drops it on another conversation',()=>{
 const currentQuery={modelingTaskId:'task',ontologyId:'model',conversationId:'chat-1'}
 expect(buildChatRouteQuery({currentQuery,conversationId:'chat-1'})).toMatchObject({modelingTaskId:'task',ontologyId:'model'})
 expect(buildChatRouteQuery({currentQuery,conversationId:'chat-2'})).not.toHaveProperty('modelingTaskId')
})

it('renders nullable server command fields without null text and preserves a zero cardinality', () => {
  const change: import('../../api/modelingTaskApi').ModelingChange = {
    kind: 'CREATE_TERM', termKind: 'OBJECT', targetId: null, name: '设备',
    domainId: null, rangeId: null, field: null, value: null, language: null,
    operator: null, propertyId: null, fillerId: null, cardinality: null,
    originalAxiomId: null, clientId: 'equipment',
  }
  const attribute = { ...change, termKind: 'ATTRIBUTE' as const, name: '设备编号', clientId: 'code', domainId: '$equipment', rangeId: 'http://www.w3.org/2001/XMLSchema#string' }
  expect(proposalLabel(change)).toBe('新增 · 设备')
  expect(proposalDetails(change, [change, attribute])).toBe('')
  expect(proposalDetails(attribute, [change, attribute])).toBe('所属对象：设备 · 关联对象：文本')
  expect(proposalDetails({ ...change, cardinality: 0 }, [change])).toBe('0')
})

it('prefers business datatype names over unlabeled projection nodes after acceptance', () => {
  expect(proposalDetails({ kind: 'CREATE_TERM', name: '设备编号', rangeId: 'http://www.w3.org/2001/XMLSchema#string' }, [], { 'http://www.w3.org/2001/XMLSchema#string': '未命名业务项' })).toBe('关联对象：文本')
})
