import { http } from '@/api'
import type { AxiosRequestConfig, AxiosRequestTransformer } from 'axios'
import { semanticError } from './semanticErrors'
import type {
  Draft,
  Revision,
  Page,
  Metadata,
  Ontology,
  SaveDraft,
  PublishDraft,
  ValidationReport,
  Diff,
  OntologyPackage,
  PackagePreview,
  PackageImportResult,
  OntologyUsagePage,
  ImpactReport,
} from './types'
const path = (id: string) => `/semantic/ontologies/${encodeURIComponent(id)}`
export function scopedConfig(workspaceId: string, signal?: AbortSignal): AxiosRequestConfig {
  const defaults = http.defaults.transformRequest
  const transforms = Array.isArray(defaults) ? defaults : defaults ? [defaults] : []
  // Axios executes transforms after all request interceptors. Keep the captured
  // workspace even when localStorage changes before dispatch.
  const pinWorkspace: AxiosRequestTransformer = (data, headers) => {
    headers.set('X-Workspace-Id', workspaceId)
    return data
  }
  return { signal, transformRequest: [pinWorkspace, ...transforms] }
}
export async function semanticRequest<T>(workspaceId: string, config: AxiosRequestConfig, signal?: AbortSignal): Promise<T> {
  try {
    const envelope = await http.request<unknown, { data: T }>({
      ...config,
      ...scopedConfig(workspaceId, signal),
    })
    return envelope.data
  } catch (error) {
    throw semanticError(error)
  }
}
export const ontologyApi = {
  status: async (signal?: AbortSignal) => {
    const result = await http.get<unknown, { data: { enabled: boolean } }>('/semantic/status', { signal })
    return result.data
  },
  list: (ws: string, q = '', page = 1, signal?: AbortSignal) =>
    semanticRequest<Page>(ws, { url: '/semantic/ontologies', params: { q, page, pageSize: 20 } }, signal),
  create: (ws: string, body: Metadata, signal?: AbortSignal) =>
    semanticRequest<Ontology>(ws, { url: '/semantic/ontologies', method: 'POST', data: body }, signal),
  get: (ws: string, id: string, signal?: AbortSignal) => semanticRequest<Ontology>(ws, { url: path(id) }, signal),
  createDraft: (ws: string, id: string, baseRevisionId: string | null = null, signal?: AbortSignal) =>
    semanticRequest<Draft>(ws, { url: `${path(id)}/draft`, method: 'POST', data: { baseRevisionId } }, signal),
  getDraft: (ws: string, id: string, signal?: AbortSignal) =>
    semanticRequest<Draft>(ws, { url: `${path(id)}/draft` }, signal),
  saveDraft: (ws: string, id: string, body: SaveDraft, signal?: AbortSignal) =>
    semanticRequest<Draft>(ws, { url: `${path(id)}/draft`, method: 'PUT', data: body }, signal),
  discard: (ws: string, id: string, expectedDraftVersion: number, signal?: AbortSignal) =>
    semanticRequest<void>(
      ws,
      { url: `${path(id)}/draft`, method: 'DELETE', params: { expectedDraftVersion } },
      signal,
    ),
  validate: (ws: string, id: string, expectedDraftVersion: number, signal?: AbortSignal) =>
    semanticRequest<ValidationReport>(
      ws,
      { url: `${path(id)}/draft/validate`, method: 'POST', data: { expectedDraftVersion } },
      signal,
    ),
  publish: (ws: string, id: string, body: PublishDraft, signal?: AbortSignal) =>
    semanticRequest<Revision>(ws, { url: `${path(id)}/draft/publish`, method: 'POST', data: body }, signal),
  revisions: (ws: string, id: string, signal?: AbortSignal) =>
    semanticRequest<Revision[]>(ws, { url: `${path(id)}/revisions` }, signal),
  revision: (ws: string, id: string, revisionId: string, signal?: AbortSignal) =>
    semanticRequest<Revision>(ws, { url: `${path(id)}/revisions/${encodeURIComponent(revisionId)}` }, signal),
  diff: (ws: string, id: string, from: string | null, to: string, signal?: AbortSignal) =>
    semanticRequest<Diff>(ws, { url: `${path(id)}/diff`, params: { from: from || undefined, to } }, signal),
  availability: (
    ws: string,
    id: string,
    revisionId: string,
    availableForNewBindings: boolean,
    signal?: AbortSignal,
  ) =>
    semanticRequest<Revision>(
      ws,
      {
        url: `${path(id)}/revisions/${encodeURIComponent(revisionId)}/availability`,
        method: 'PATCH',
        data: { availableForNewBindings },
      },
      signal,
    ),
  operation: (ws: string, operationId: string, signal?: AbortSignal) =>
    semanticRequest<{ operationId: string; kind: string; resourceId: string; result: Revision }>(
      ws,
      { url: `/semantic/operations/${encodeURIComponent(operationId)}` },
      signal,
    ),
  packageForRevision: (ws: string, id: string, revisionId: string, signal?: AbortSignal) =>
    semanticRequest<OntologyPackage>(
      ws,
      { url: `${path(id)}/revisions/${encodeURIComponent(revisionId)}/package` },
      signal,
    ),
  previewPackage: (ws: string, body: OntologyPackage | string, signal?: AbortSignal) =>
    semanticRequest<PackagePreview>(ws, { url: '/semantic/ontology-packages/preview', method: 'POST', data: body, headers: { 'Content-Type': 'application/json' } }, signal),
  importPackage: (
    ws: string,
    body: { package: OntologyPackage; expectedDigest: string; operationId: string; name: string },
    signal?: AbortSignal,
  ) =>
    semanticRequest<PackageImportResult>(
      ws,
      { url: '/semantic/ontology-packages/import', method: 'POST', data: body },
      signal,
    ),
  packageImport: (ws: string, operationId: string, signal?: AbortSignal) =>
    semanticRequest<PackageImportResult>(
      ws,
      { url: `/semantic/ontology-package-imports/${encodeURIComponent(operationId)}` },
      signal,
    ),
  usage: (ws: string, id: string, page = 1, pageSize = 20, signal?: AbortSignal) =>
    semanticRequest<OntologyUsagePage>(
      ws,
      { url: `${path(id)}/usage`, params: { page, pageSize } },
      signal,
    ),
  impact: (
    ws: string,
    id: string,
    body: { graphId: string; targetRevisionId?: string; expectedDraftVersion?: number },
    signal?: AbortSignal,
  ) => semanticRequest<ImpactReport>(ws, { url: `${path(id)}/impact`, method: 'POST', data: body }, signal),
}
export type OntologyApi = typeof ontologyApi
