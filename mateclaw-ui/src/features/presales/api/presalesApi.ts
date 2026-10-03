import {
  decodeProject,
  decodeProjectPage,
  decodeRepairContext,
  decodeMembers,
  decodeSources,
  decodeStatements,
  decodeEmployees,
  decodeCapabilities,
} from './presalesResponse'
import { workspaceRequest } from '@/api/workspaceRequest'
import type { AxiosRequestConfig } from 'axios'
import type { PresalesCommandIntent } from './presalesCommandTypes'
export type {
  PresalesCommandAction,
  PresalesCommandIntent,
  PresalesCommandPayloads,
} from './presalesCommandTypes'
// Heterogeneous historical records omit fields; opaque source snapshots remain unknown.
export interface PresalesRecord {
  id?: string
  [key: string]: unknown
  version?: number
  createdBy?: string
  createdAt?: string
  updatedBy?: string
  updatedAt?: string
  enabled?: boolean
  artifactId?: string
  files?: { filename: string }[]
  name?: string
  title?: string
  text?: string
  status?: string
  role?: string
  description?: string
  originKind?: string
  priority?: string
  scope?: string
  authority?: string
  customerConfirmationStatus?: string
  userId?: string
  nickname?: string
  username?: string
  kbId?: string
  graphId?: string
  ontologyRevisionId?: string
  revision?: number
  label?: string
  requirementId?: string
  statementId?: string
  statementRevision?: string | number
  proposedByTaskId?: string
  question?: string
  impact?: string
  ownerId?: string
  answer?: string
  answerSourceId?: string
  reason?: string
  evidenceText?: string
  productVersion?: string
  baselineId?: string
  solutionId?: string
  purpose?: string
  summary?: string
  severity?: string
  operationId?: string
  skill?: string
  conversationId?: string
  agentId?: string
  agentName?: string
  runId?: string
  queueState?: string
  error?: string
  requirementRefs?: string[]
  evidenceIds?: string[]
  evidenceRefs?: string[]
  sourceRefs?: string[]
  sections?: PresalesRecord[]
  references?: PresalesRecord[]
  requirementResponses?: PresalesRecord[]
  issues?: PresalesRecord[]
  sourceSnapshot?: unknown
  presentation?: PresalesPresentation
  coverage?: PresalesCoverage
  contextSnapshot?: PresalesContextSnapshot
  result?: PresalesGenerationResult
}
/** Query projections have their own nullability; they are not business records. */
export interface PresalesMember {
  [key: string]: unknown
  id: string
  workspaceId: string
  userId: string
  role: string
  nickname?: string | null
  username?: string | null
}
interface PresalesSourceMetadata {
  [key: string]: unknown
  kbId: string
  name: string | null
}
export interface PresalesKnowledgeBaseSource extends PresalesSourceMetadata {
  graphId?: never
  ontologyRevisionId?: never
}
export interface PresalesGraphSource extends PresalesSourceMetadata {
  graphId: string | null
  ontologyRevisionId: string | null
}
export type PresalesSource = PresalesKnowledgeBaseSource | PresalesGraphSource
export interface PresalesTrustedStatement {
  [key: string]: unknown
  id: string
  revision: number
  graphId: string
  ontologyRevisionId: string | null
  label: string
  evidenceIds: (string | null)[] | null
}
export interface PresalesEmployee {
  [key: string]: unknown
  id: string
  name: string
  enabled: boolean
  available: boolean
}
export interface PresalesEntity extends PresalesRecord {
  id: string
}
export interface PresalesPresentation extends PresalesRecord {
  artifactId: string
  skill: string
  skillVersion: string
  pageCount: number
  slides?: { filename: string; title?: string }[]
}
export interface PresalesCoverage {
  applicable: boolean
  handledIn: number
  totalIn: number
  responses?: PresalesRecord[]
}
export interface PresalesContextSnapshot extends PresalesRecord {
  truncated?: boolean
}
export interface PresalesGenerationResult {
  items?: PresalesRecord[]
  solution?: PresalesRecord
  unknowns?: unknown
  assumptions?: unknown
}
export interface PresalesEditorForm extends PresalesRecord {
  customer?: string
  industry?: string
  goal?: string
}
/** List items do not load the business collections required by project details. */
export interface PresalesProjectSummary extends PresalesEntity {
  workspaceId: string
  version: number
  name: string
  customer: string
  ownerId: string
  status: string
  industry?: string
  goal?: string
  stage?: string
  updatedAt?: string
  openClarificationCount?: number
  latestSolutionVersion?: number
}
export interface PresalesProject extends PresalesProjectSummary {
  sourceAccessRestricted?: boolean
  repairBindings?: { id: string; role: string }[]
  materials: PresalesEntity[]
  requirements: PresalesEntity[]
  clarifications: PresalesEntity[]
  baselines: PresalesEntity[]
  fitGaps: PresalesEntity[]
  solutions: PresalesEntity[]
  reviews: PresalesEntity[]
  releases: PresalesEntity[]
  cases?: PresalesEntity[]
  tasks?: PresalesTask[]
  context?: unknown
  contextCards?: PresalesEntity[]
  reviewDrafts?: PresalesEntity[]
}
export interface PresalesRepairContext {
  id: string
  workspaceId: string
  version: number
  name: string
  customer: string
  ownerId: string
  status: string
  industry?: string
  goal?: string
  stage?: string
  agentId?: string
  agentName?: string
  createdBy?: string
  createdAt?: string
  updatedBy?: string
  updatedAt?: string
  sourceAccessRestricted: true
  repairBindings: { id: string; role: string }[]
  materials: never[]
  requirements: never[]
  clarifications: never[]
  baselines: never[]
  fitGaps: never[]
  cases: never[]
  solutions: never[]
  reviews: never[]
  reviewDrafts: never[]
  releases: never[]
  tasks: never[]
  contextCards: never[]
}
export interface PresalesTask extends PresalesEntity {
  operationId?: string
  status: string
  skill?: string
}
export interface PresalesCapabilities {
  enabled: boolean
  semanticEnabled: boolean
  canWrite: boolean
  canApprove: boolean
  modelConfigured?: boolean
}
export interface ProjectPage {
  items: PresalesProjectSummary[]
  total: number | string
  page: number
  pageSize: number
}
/** UI intent contracts; payload JSON still requires the server's domain validation. */
export interface PresalesVersionedMutation {
  expectedVersion: number
  operationId: string
}
export interface PresalesProjectWrite extends PresalesVersionedMutation {
  name?: string
  customer?: string
  ownerId?: string
  agentId?: string
  industry?: string
  goal?: string
}
export interface PresalesProjectCreate extends Omit<PresalesProjectWrite, 'expectedVersion'> {
  expectedVersion: 0
}
export type PresalesCommandRequest = PresalesVersionedMutation & PresalesCommandIntent
export type PresalesSkill = 'S1' | 'S2' | 'S3' | 'S4' | 'S5' | 'S6' | 'S7' | 'S8'
export interface PresalesGenerateRequest extends PresalesVersionedMutation {
  skill: PresalesSkill
  taskGoal: string
}
export interface PresalesCancelRequest {
  operationId: string
}
export interface PresalesListQuery {
  q?: string
  status?: string
  ownerId?: string
  stage?: string
  page?: number
  pageSize?: number
}
async function request<T>(
  workspaceId: string,
  config: AxiosRequestConfig,
  signal?: AbortSignal,
): Promise<T> {
  const response = await workspaceRequest<{ data: T }>(workspaceId, config, signal)
  return response.data
}
async function requestProject(
  ws: string,
  config: AxiosRequestConfig,
  id?: string,
  signal?: AbortSignal,
) {
  return decodeProject(await request<unknown>(ws, config, signal), ws, id)
}
const projectPath = (id: string) => `/presales/projects/${encodeURIComponent(id)}`
export const presalesApi = {
  members: (ws: string, signal?: AbortSignal) =>
    request<unknown>(ws, { url: `/workspaces/${encodeURIComponent(ws)}/members` }, signal).then(
      (value) => decodeMembers(value, ws),
    ),
  sources: (ws: string) => request<unknown>(ws, { url: '/presales/sources' }).then(decodeSources),
  statements: (ws: string, id: string) =>
    request<unknown>(ws, { url: `${projectPath(id)}/statements` }).then(decodeStatements),
  handoff: (ws: string, id: string) => request<unknown>(ws, { url: `${projectPath(id)}/handoff` }),
  file: (
    ws: string,
    id: string,
    versionId: string,
    filename: string,
    kind: 'files' | 'preview' | 'draft',
  ) =>
    workspaceRequest<Blob>(ws, {
      url: `${projectPath(id)}/${kind === 'draft' ? 'solutions' : 'releases'}/${encodeURIComponent(versionId)}/${kind}/${encodeURIComponent(filename)}`,
      responseType: 'blob',
    }),
  capabilities: (ws: string, signal?: AbortSignal) =>
    request<unknown>(ws, { url: '/presales/capabilities' }, signal).then(decodeCapabilities),
  list: (ws: string, params: PresalesListQuery, signal?: AbortSignal) =>
    request<unknown>(ws, { url: '/presales/projects', params }, signal).then((value) =>
      decodeProjectPage(value, ws),
    ),
  get: (ws: string, id: string, signal?: AbortSignal) =>
    requestProject(ws, { url: projectPath(id) }, id, signal),
  repairContext: (ws: string, id: string, signal?: AbortSignal) =>
    request<unknown>(ws, { url: `${projectPath(id)}/repair-context` }, signal).then((value) =>
      decodeRepairContext(value, ws, id),
    ),
  create: (ws: string, data: PresalesProjectCreate) =>
    requestProject(ws, {
      url: '/presales/projects',
      method: 'POST',
      data,
    }),
  update: (ws: string, id: string, data: PresalesProjectWrite) =>
    requestProject(
      ws,
      {
        url: projectPath(id),
        method: 'PATCH',
        data,
      },
      id,
    ),
  command: (ws: string, id: string, data: PresalesCommandRequest) =>
    requestProject(
      ws,
      {
        url: `${projectPath(id)}/commands`,
        method: 'POST',
        data,
      },
      id,
    ),
  evidence: (ws: string, id: string, graphId: string, evidenceId: string) =>
    request<unknown>(ws, {
      url: `${projectPath(id)}/evidence`,
      params: { graphId, evidenceId },
    }),
  employees: (ws: string, signal?: AbortSignal) =>
    request<unknown>(ws, { url: '/presales/employees' }, signal).then(decodeEmployees),
  generate: (ws: string, id: string, data: PresalesGenerateRequest) =>
    requestProject(
      ws,
      {
        url: `${projectPath(id)}/generate`,
        method: 'POST',
        data,
      },
      id,
    ),
  cancelTask: (ws: string, id: string, taskId: string, data: PresalesCancelRequest) =>
    requestProject(
      ws,
      {
        url: `${projectPath(id)}/tasks/${encodeURIComponent(taskId)}/cancel`,
        method: 'POST',
        data,
      },
      id,
    ),
}
