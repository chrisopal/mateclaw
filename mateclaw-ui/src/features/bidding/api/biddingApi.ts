import type { AxiosRequestConfig, AxiosRequestTransformer } from 'axios'
import { http } from '@/api'
import { scopedConfig } from '@/features/semantic/api/ontologyApi'
import type { AnalysisView, Capabilities, Command, CommandResult, Dashboard, Employee, Evidence, Member, Project, ProjectPage, Source, TaskDetails, TaskPage } from './types'

async function request<T>(workspaceId: string, config: AxiosRequestConfig, signal?: AbortSignal): Promise<T> {
  const response = await http.request<unknown, { data: T }>({ ...config, ...scopedConfig(workspaceId, signal) })
  return response.data
}
const base = '/bidding'
const projectPath = (id: string) => `${base}/projects/${encodeURIComponent(id)}`
export const biddingApi = {
  capabilities: (ws: string, signal?: AbortSignal) => request<Capabilities>(ws, { url: `${base}/capabilities` }, signal),
  members: (ws: string, signal?: AbortSignal) => request<Member[]>(ws, { url: `/workspaces/${encodeURIComponent(ws)}/members` }, signal),
  employees: (ws: string, signal?: AbortSignal) => request<Employee[]>(ws, { url: `${base}/employees` }, signal),
  dashboard: (ws: string, params: Record<string,string>, signal?: AbortSignal) => request<Dashboard>(ws, { url: `${base}/dashboard`, params }, signal),
  list: (ws: string, params: Record<string, string | number>, signal?: AbortSignal) => request<ProjectPage>(ws, { url: `${base}/projects`, params }, signal),
  get: (ws: string, id: string, signal?: AbortSignal) => request<Project>(ws, { url: projectPath(id) }, signal),
  create: (ws: string, data: { operationId: string; name: string; lotName: string; ownerId: string }) => request<Project>(ws, { url: `${base}/projects`, method: 'POST', data }),
  command: (ws: string, id: string, data: Command) => request<CommandResult>(ws, { url: `${projectPath(id)}/commands`, method: 'POST', data }),
  sources: async (ws: string, id: string, signal?: AbortSignal) => (await request<Source[]>(ws, { url: `${projectPath(id)}/sources` }, signal)).map(source => ({ ...source, sourceId: source.sourceId || source.id || '', digest: source.digest || source.sha256 || '', readStatus: source.readStatus || 'UNKNOWN' })),
  sourceSetHead: (ws: string, id: string, signal?: AbortSignal) => request<{ ref?: Project['ref']; sources?: Source[] } | null>(ws, { url: `${projectPath(id)}/source-set/head` }, signal),
  upload: (ws: string, id: string, file: File, sourceKind: string, operationId: string, supersedesRef?: string) => {
    const data = new FormData(); data.append('file', file); data.append('sourceKind', sourceKind); data.append('operationId', operationId)
    if (supersedesRef) data.append('supersedesRef', supersedesRef)
    const transforms = http.defaults.transformRequest
    const defaults = Array.isArray(transforms) ? transforms : transforms ? [transforms] : []
    const pin: AxiosRequestTransformer = (_value, headers) => { headers.set('X-Workspace-Id', ws); return data }
    return request<Source>(ws, { url: `${projectPath(id)}/sources`, method: 'POST', data, headers: { 'Content-Type': 'multipart/form-data' }, transformRequest: [pin, ...defaults] })
  },
  content: (ws: string, id: string, sourceId: string, version: number, signal?: AbortSignal) => http.get<unknown, Blob>(`${projectPath(id)}/sources/${encodeURIComponent(sourceId)}/versions/${version}/content`, { ...scopedConfig(ws, signal), responseType: 'blob' }),
  evidence: (ws: string, id: string, input: { sourceId: string; version: number; blockId: string }, signal?: AbortSignal) => request<Evidence>(ws, { url: `${projectPath(id)}/evidence`, params: input }, signal),
  analysis: (ws: string, id: string, signal?: AbortSignal) => request<AnalysisView>(ws, { url: `${projectPath(id)}/analysis` }, signal),
  revision: (ws: string, id: string, revisionId: string, signal?: AbortSignal) => request<Record<string, unknown>>(ws, { url: `${projectPath(id)}/revisions/${encodeURIComponent(revisionId)}` }, signal),
  tasks: (ws: string, id: string, signal?: AbortSignal) => request<TaskPage>(ws, { url: `${projectPath(id)}/tasks`, params: { page: 1, pageSize: 100 } }, signal),
  task: (ws: string, taskId: string, signal?: AbortSignal) => request<TaskDetails>(ws, { url: `${base}/tasks/${encodeURIComponent(taskId)}` }, signal),
}
