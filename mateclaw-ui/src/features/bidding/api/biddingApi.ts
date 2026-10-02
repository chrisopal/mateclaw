import type { AxiosRequestConfig } from 'axios'
import { workspaceRequest } from '@/api/workspaceRequest'
import type {
  AnalysisView,
  ArtifactApprovalContext,
  ArtifactMetadata,
  BiddingMaterialsView,
  BiddingReviewView,
  Capabilities,
  ChangeImpactView,
  Command,
  CommandResult,
  Dashboard,
  Employee,
  Evidence,
  ExportTemplate,
  HandoffOption,
  HandoffSnapshot,
  Member,
  OutlineView,
  Project,
  ProjectPage,
  Source,
  TaskDetails,
  TaskPage,
  WritingView,
} from './types'

async function request<T>(
  workspaceId: string,
  config: AxiosRequestConfig,
  signal?: AbortSignal,
): Promise<T> {
  const response = await workspaceRequest<{ data: T }>(workspaceId, config, signal)
  return response.data
}
const base = '/bidding'
const projectPath = (id: string) => `${base}/projects/${encodeURIComponent(id)}`
export const biddingApi = {
  capabilities: (ws: string, signal?: AbortSignal) =>
    request<Capabilities>(ws, { url: `${base}/capabilities` }, signal),
  members: (ws: string, signal?: AbortSignal) =>
    request<Member[]>(ws, { url: `/workspaces/${encodeURIComponent(ws)}/members` }, signal),
  employees: (ws: string, signal?: AbortSignal) =>
    request<Employee[]>(ws, { url: `${base}/employees` }, signal),
  dashboard: (ws: string, params: Record<string, string>, signal?: AbortSignal) =>
    request<Dashboard>(ws, { url: `${base}/dashboard`, params }, signal),
  list: (ws: string, params: Record<string, string | number>, signal?: AbortSignal) =>
    request<ProjectPage>(ws, { url: `${base}/projects`, params }, signal),
  get: (ws: string, id: string, signal?: AbortSignal) =>
    request<Project>(ws, { url: projectPath(id) }, signal),
  create: (
    ws: string,
    data: { operationId: string; name: string; lotName: string; ownerId: string },
  ) => request<Project>(ws, { url: `${base}/projects`, method: 'POST', data }),
  command: (ws: string, id: string, data: Command) =>
    request<CommandResult>(ws, { url: `${projectPath(id)}/commands`, method: 'POST', data }),
  handoffOptions: (ws: string, presalesProjectId: string, signal?: AbortSignal) =>
    request<HandoffOption[]>(
      ws,
      { url: `${base}/handoff-options`, params: { presalesProjectId } },
      signal,
    ),
  handoffSnapshot: (
    ws: string,
    presalesProjectId: string,
    releaseId: string,
    signal?: AbortSignal,
  ) =>
    request<HandoffSnapshot>(
      ws,
      {
        url: `/presales/projects/${encodeURIComponent(presalesProjectId)}/releases/${encodeURIComponent(releaseId)}/handoff`,
      },
      signal,
    ),
  materials: (ws: string, id: string, signal?: AbortSignal) =>
    request<BiddingMaterialsView>(ws, { url: `${projectPath(id)}/materials` }, signal),
  outline: (ws: string, id: string, signal?: AbortSignal) =>
    request<OutlineView>(ws, { url: `${projectPath(id)}/outline` }, signal),
  writing: (ws: string, id: string, signal?: AbortSignal) =>
    request<WritingView>(ws, { url: `${projectPath(id)}/writing` }, signal),
  changeImpact: (ws: string, id: string, signal?: AbortSignal) =>
    request<ChangeImpactView>(ws, { url: `${projectPath(id)}/change-impact` }, signal),
  knowledgeBases: (ws: string, signal?: AbortSignal) =>
    request<{ id: string | number; name: string }[]>(ws, { url: '/wiki/knowledge-bases' }, signal),
  knowledgePages: (ws: string, knowledgeBaseId: string, signal?: AbortSignal) =>
    request<{ id: string; slug: string; title: string; pageType?: string; archived?: boolean }[]>(
      ws,
      { url: `/wiki/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/pages` },
      signal,
    ),
  knowledgePage: (ws: string, knowledgeBaseId: string, slug: string, signal?: AbortSignal) =>
    request<{
      id: string
      slug: string
      title: string
      content?: string
      pageType?: string
      archived?: boolean
    }>(
      ws,
      {
        url: `/wiki/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/pages/${encodeURIComponent(slug)}`,
      },
      signal,
    ),
  sources: async (ws: string, id: string, signal?: AbortSignal) =>
    (await request<Source[]>(ws, { url: `${projectPath(id)}/sources` }, signal)).map((source) => ({
      ...source,
      sourceId: source.sourceId || source.id || '',
      digest: source.digest || source.sha256 || '',
      readStatus: source.readStatus || 'UNKNOWN',
    })),
  sourceSetHead: (ws: string, id: string, signal?: AbortSignal) =>
    request<{ ref?: Project['ref']; sources?: Source[] } | null>(
      ws,
      { url: `${projectPath(id)}/source-set/head` },
      signal,
    ),
  upload: (
    ws: string,
    id: string,
    file: File,
    sourceKind: string,
    operationId: string,
    supersedesRef?: string,
  ) => {
    const data = new FormData()
    data.append('file', file)
    data.append('sourceKind', sourceKind)
    data.append('operationId', operationId)
    if (supersedesRef) data.append('supersedesRef', supersedesRef)
    return request<Source>(ws, { url: `${projectPath(id)}/sources`, method: 'POST', data })
  },
  content: (ws: string, id: string, sourceId: string, version: number, signal?: AbortSignal) =>
    workspaceRequest<Blob>(
      ws,
      {
        url: `${projectPath(id)}/sources/${encodeURIComponent(sourceId)}/versions/${version}/content`,
        responseType: 'blob',
      },
      signal,
    ),
  evidence: (
    ws: string,
    id: string,
    input: { sourceId: string; version: number; blockId: string },
    signal?: AbortSignal,
  ) => request<Evidence>(ws, { url: `${projectPath(id)}/evidence`, params: input }, signal),
  analysis: (ws: string, id: string, signal?: AbortSignal) =>
    request<AnalysisView>(ws, { url: `${projectPath(id)}/analysis` }, signal),
  revision: (ws: string, id: string, revisionId: string, signal?: AbortSignal) =>
    request<Record<string, unknown>>(
      ws,
      { url: `${projectPath(id)}/revisions/${encodeURIComponent(revisionId)}` },
      signal,
    ),
  tasks: (ws: string, id: string, signal?: AbortSignal, page = 1, pageSize = 100) =>
    request<TaskPage>(ws, { url: `${projectPath(id)}/tasks`, params: { page, pageSize } }, signal),
  task: (ws: string, taskId: string, signal?: AbortSignal) =>
    request<TaskDetails>(ws, { url: `${base}/tasks/${encodeURIComponent(taskId)}` }, signal),
  review: (ws: string, id: string, signal?: AbortSignal) =>
    request<BiddingReviewView>(ws, { url: `${projectPath(id)}/review` }, signal),
  templates: (ws: string, id: string, signal?: AbortSignal) =>
    request<ExportTemplate[]>(ws, { url: `${projectPath(id)}/templates` }, signal),
  artifact: (ws: string, id: string, artifactId: string, signal?: AbortSignal) =>
    request<ArtifactMetadata>(
      ws,
      { url: `${projectPath(id)}/artifacts/${encodeURIComponent(artifactId)}` },
      signal,
    ),
  approvalContext: (ws: string, id: string, artifactId: string, signal?: AbortSignal) =>
    request<ArtifactApprovalContext>(
      ws,
      { url: `${projectPath(id)}/artifacts/${encodeURIComponent(artifactId)}/approval-context` },
      signal,
    ),
  download: (
    ws: string,
    id: string,
    artifactId: string,
    mode: 'candidate' | 'preview' | 'formal',
    signal?: AbortSignal,
  ): Promise<Blob> =>
    workspaceRequest<Blob>(
      ws,
      {
        url: `${projectPath(id)}/artifacts/${encodeURIComponent(artifactId)}/content`,
        params: { mode },
        responseType: 'blob',
      },
      signal,
    ),
}
