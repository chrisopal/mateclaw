import type { ModelChange } from '../ontology/businessModel'
import { semanticRequest } from './ontologyApi'
export interface ModelingSource {
  knowledgeBaseId: string
  sourceRef: string
  sourceDigest: string
}
// Jackson returns unset command fields as null; keep the business command vocabulary.
export type ModelingChange = {
  [K in keyof ModelChange]: K extends 'kind' ? ModelChange[K] : ModelChange[K] | null
}
export interface ModelingProposal {
  id: string
  status: string
  reason?: string
  answers: Record<string, string>
  input: {
    changes: ModelingChange[]
    questions: string[]
    samples: unknown[]
    evidence: Array<{
      clientId: string
      exactQuote: string
      origin: string
      sourceRef?: string
    }>
  }
}
export interface ModelingTask {
  id: string
  ontologyId: string
  draftId: string
  goal: string
  sources: ModelingSource[]
  stage: string
  message?: string
  proposals: ModelingProposal[]
}
export interface CreateModelingTask {
  operationId: string
  ontologyId?: string
  newOntology?: { name: string; description: string }
  goal: string
  sources: ModelingSource[]
}
const path = (id: string) =>
  `/semantic/modeling-tasks/${encodeURIComponent(id)}`
export const modelingTaskApi = {
  create: (ws: string, data: CreateModelingTask, signal?: AbortSignal) =>
    semanticRequest<ModelingTask>(
      ws,
      { url: '/semantic/modeling-tasks', method: 'POST', data },
      signal,
    ),
  list: (ws: string, ontologyId?: string, signal?: AbortSignal) =>
    semanticRequest<ModelingTask[]>(
      ws,
      { url: '/semantic/modeling-tasks', params: { ontologyId } },
      signal,
    ),
  get: (ws: string, id: string, signal?: AbortSignal) =>
    semanticRequest<ModelingTask>(ws, { url: path(id) }, signal),
  decide: (
    ws: string,
    id: string,
    proposalId: string,
    data: {
      operationId: string
      decision: 'ACCEPT' | 'REJECT'
      answers: Record<string, string>
    },
    signal?: AbortSignal,
  ) =>
    semanticRequest<ModelingTask>(
      ws,
      {
        url: `${path(id)}/proposals/${encodeURIComponent(proposalId)}/decision`,
        method: 'POST',
        data,
      },
      signal,
    ),
}
export function modelingTaskPrompt(taskId: string) {
  return `请恢复业务建模任务 ${taskId}，读取任务目标、已选资料和当前业务草稿，继续生成业务建议并保存到这个任务。不要创建另一个任务，不要代替用户确认建议或发布。`
}
export function proposalLabel(change: ModelingChange) {
  const kind: Record<ModelChange['kind'], string> = {
    CREATE_TERM: '新增',
    REPLACE_DEFINITION: '修改定义',
    REPLACE_RESTRICTION: '修改规则',
  }
  return `${kind[change.kind]} · ${change.name || (change.value && !/^(?:https?:|urn:|\$)/.test(change.value) ? change.value : '') || '业务规则'}`
}

export function proposalDetails(
  change: ModelingChange,
  changes: ModelingChange[],
  existing: Record<string, string> = {},
) {
  const names = { ...existing }
  changes.forEach((c) => {
    if (c.clientId && c.name) names['$' + c.clientId] = c.name
  })
  const datatypes: Record<string, string> = {
    string: '文本',
    integer: '整数',
    decimal: '小数',
    boolean: '是或否',
    date: '日期',
    dateTime: '日期时间',
    double: '数值',
    float: '数值',
  }
  const name = (id?: string | null) =>
    id
      ? id.startsWith('http://www.w3.org/2001/XMLSchema#')
        ? datatypes[id.split('#')[1]!] || '数据类型'
        : names[id] || '未命名业务项'
      : ''
  const fields: Record<string, string> = {
    NAME: '名称',
    DESCRIPTION: '说明',
    PARENT: '上级对象',
    LABEL: '名称',
    COMMENT: '说明',
    DEFINITION: '定义',
    DOMAIN: '所属对象',
    RANGE: '关联对象',
    REQUIRED: '必填',
    UNIT: '单位',
    SINGLE_VALUE: '单值',
    SUBCLASS: '上级对象',
  }
  const operators: Record<string, string> = {
    SOME: '至少存在一个',
    ALL: '所有关联对象',
    MIN: '至少',
    MAX: '至多',
    EXACT: '恰好',
    ObjectSomeValuesFrom: '至少存在一个',
    ObjectAllValuesFrom: '所有关联对象',
  }
  return [
    change.targetId ? name(change.targetId) : '',
    change.field ? fields[change.field] || '业务属性' : '',
    change.value && ['PARENT', 'DOMAIN', 'RANGE'].includes(change.field || '')
      ? name(change.value)
      : '',
    change.domainId ? '所属对象：' + name(change.domainId) : '',
    change.rangeId ? '关联对象：' + name(change.rangeId) : '',
    change.propertyId ? '关系：' + name(change.propertyId) : '',
    change.operator ? operators[change.operator] || '关系限制' : '',
    change.cardinality != null ? String(change.cardinality) : '',
    change.fillerId ? name(change.fillerId) : '',
  ]
    .filter(Boolean)
    .join(' · ')
}
