import { http } from '@/api'
import { scopedConfig } from '@/features/semantic/api/ontologyApi'
import type { AxiosRequestConfig } from 'axios'
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
export interface PresalesMember extends PresalesRecord {
  userId: string
}
export interface PresalesSource extends PresalesRecord {
  kbId: string
  name: string
}
export interface PresalesEmployee extends PresalesRecord {
  id: string
  name: string
  enabled: boolean
  available?: boolean
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
export interface PresalesProject extends PresalesEntity {
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
  sourceAccessRestricted?: boolean
  materials: PresalesEntity[]
  requirements: PresalesEntity[]
  clarifications: PresalesEntity[]
  baselines: PresalesEntity[]
  fitGaps: PresalesEntity[]
  solutions: PresalesEntity[]
  reviews: PresalesEntity[]
  releases: PresalesEntity[]
  tasks?: PresalesTask[]
  context?: unknown
  contextCards?: PresalesEntity[]
  reviewDrafts?: PresalesEntity[]
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
  items: PresalesProject[]
  total: number
  page: number
  pageSize: number
}
async function request<T>(
  workspaceId: string,
  config: AxiosRequestConfig,
  signal?: AbortSignal,
): Promise<T> {
  const response = await http.request<unknown, { data: T }>({
    ...config,
    ...scopedConfig(workspaceId, signal),
  })
  return response.data
}
const projectPath = (id: string) => `/presales/projects/${encodeURIComponent(id)}`
export const presalesApi = {
  members: (ws: string, signal?: AbortSignal) =>
    request<PresalesMember[]>(ws, { url: `/workspaces/${encodeURIComponent(ws)}/members` }, signal),
  sources: (ws: string) => request<PresalesSource[]>(ws, { url: '/presales/sources' }),
  statements: (ws: string, id: string) =>
    request<PresalesRecord[]>(ws, { url: `${projectPath(id)}/statements` }),
  handoff: (ws: string, id: string) => request<unknown>(ws, { url: `${projectPath(id)}/handoff` }),
  file: (
    ws: string,
    id: string,
    versionId: string,
    filename: string,
    kind: 'files' | 'preview' | 'draft',
  ) =>
    http.get<unknown, Blob>(
      `${projectPath(id)}/${kind === 'draft' ? 'solutions' : 'releases'}/${encodeURIComponent(versionId)}/${kind}/${encodeURIComponent(filename)}`,
      { ...scopedConfig(ws), responseType: 'blob' },
    ),
  capabilities: (ws: string, signal?: AbortSignal) =>
    request<PresalesCapabilities>(ws, { url: '/presales/capabilities' }, signal),
  list: (ws: string, params: Record<string, string | number>, signal?: AbortSignal) =>
    request<ProjectPage>(ws, { url: '/presales/projects', params }, signal),
  get: (ws: string, id: string, signal?: AbortSignal) =>
    request<PresalesProject>(ws, { url: projectPath(id) }, signal),
  create: (ws: string, data: object) =>
    request<PresalesProject>(ws, {
      url: '/presales/projects',
      method: 'POST',
      data,
    }),
  update: (ws: string, id: string, data: object) =>
    request<PresalesProject>(ws, {
      url: projectPath(id),
      method: 'PATCH',
      data,
    }),
  command: (ws: string, id: string, data: object) =>
    request<PresalesProject>(ws, {
      url: `${projectPath(id)}/commands`,
      method: 'POST',
      data,
    }),
  evidence: (ws: string, id: string, graphId: string, evidenceId: string) =>
    request<unknown>(ws, {
      url: `${projectPath(id)}/evidence`,
      params: { graphId, evidenceId },
    }),
  employees: (ws: string, signal?: AbortSignal) =>
    request<PresalesEmployee[]>(ws, { url: '/presales/employees' }, signal),
  generate: (ws: string, id: string, data: object) =>
    request<PresalesProject>(ws, {
      url: `${projectPath(id)}/generate`,
      method: 'POST',
      data,
    }),
  cancelTask: (ws: string, id: string, taskId: string, data: object) =>
    request<PresalesProject>(ws, {
      url: `${projectPath(id)}/tasks/${encodeURIComponent(taskId)}/cancel`,
      method: 'POST',
      data,
    }),
}
