import { http } from '@/api'
import { scopedConfig } from '@/features/semantic/api/ontologyApi'
import type { AxiosRequestConfig } from 'axios'
export interface PresalesRecord { id: string; [key: string]: any }
export interface PresalesProject extends PresalesRecord {
  workspaceId: string; version: number; name: string; customer: string; ownerId: string; status: string
  materials: PresalesRecord[]; requirements: PresalesRecord[]; clarifications: PresalesRecord[]
  baselines: PresalesRecord[]; fitGaps: PresalesRecord[]; solutions: PresalesRecord[]
  reviews: PresalesRecord[]; releases: PresalesRecord[]
}
export interface PresalesCapabilities { enabled: boolean; semanticEnabled: boolean; canWrite: boolean; canApprove: boolean; modelConfigured?: boolean }
export interface ProjectPage { items: PresalesProject[]; total: number; page: number; pageSize: number }
async function request<T>(workspaceId: string, config: AxiosRequestConfig, signal?: AbortSignal): Promise<T> {
  const response = await http.request<unknown, { data: T }>({ ...config, ...scopedConfig(workspaceId, signal) })
  return response.data
}
const projectPath = (id: string) => `/presales/projects/${encodeURIComponent(id)}`
export const presalesApi = {
  members: (ws: string, signal?: AbortSignal) => request<PresalesRecord[]>(ws, { url: `/workspaces/${encodeURIComponent(ws)}/members` }, signal),
  sources: (ws: string) => request<PresalesRecord[]>(ws, { url: '/presales/sources' }),
  statements: (ws: string, id: string) => request<PresalesRecord[]>(ws, { url: `${projectPath(id)}/statements` }),
  handoff: (ws: string, id: string) => request<PresalesRecord>(ws, { url: `${projectPath(id)}/handoff` }),
  file: (ws: string, id: string, versionId: string, filename: string, kind: 'files' | 'preview' | 'draft') => http.get<unknown, Blob>(`${projectPath(id)}/${kind === 'draft' ? 'solutions' : 'releases'}/${encodeURIComponent(versionId)}/${kind}/${encodeURIComponent(filename)}`, { ...scopedConfig(ws), responseType: 'blob' }),
  capabilities: (ws: string, signal?: AbortSignal) => request<PresalesCapabilities>(ws, { url: '/presales/capabilities' }, signal),
  list: (ws: string, params: Record<string, string | number>, signal?: AbortSignal) => request<ProjectPage>(ws, { url: '/presales/projects', params }, signal),
  get: (ws: string, id: string, signal?: AbortSignal) => request<PresalesProject>(ws, { url: projectPath(id) }, signal),
  create: (ws: string, data: object) => request<PresalesProject>(ws, { url: '/presales/projects', method: 'POST', data }),
  update: (ws: string, id: string, data: object) => request<PresalesProject>(ws, { url: projectPath(id), method: 'PATCH', data }),
  command: (ws: string, id: string, data: object) => request<PresalesProject>(ws, { url: `${projectPath(id)}/commands`, method: 'POST', data }),
  evidence: (ws: string, id: string, graphId: string, evidenceId: string) => request<PresalesRecord>(ws, { url: `${projectPath(id)}/evidence`, params: { graphId, evidenceId } }),
  employees: (ws: string, signal?: AbortSignal) => request<PresalesRecord[]>(ws, { url: '/presales/employees' }, signal),
  generate: (ws: string, id: string, data: object) => request<PresalesProject>(ws, { url: `${projectPath(id)}/generate`, method: 'POST', data, timeout: 180000 }),
}
