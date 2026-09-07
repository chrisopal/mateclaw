import { semanticRequest } from './ontologyApi'
import type { Evidence, GraphDetail, GraphResult, Statement } from './workbenchTypes'
export const graphPath = (id: string) => `/semantic/graphs/${encodeURIComponent(id)}`
export const graphQueryApi = {
  detail: (ws: string, graph: string, signal?: AbortSignal) => semanticRequest<GraphDetail>(ws, { url: graphPath(graph) }, signal),
  neighbors: (ws: string, graph: string, entityId: string, signal?: AbortSignal) => semanticRequest<GraphResult>(ws, { url: `${graphPath(graph)}/neighbors`, params: { entityId, depth: 2, nodeLimit: 100, edgeLimit: 200 } }, signal),
  evidence: (ws: string, graph: string, id: string, signal?: AbortSignal) => semanticRequest<Evidence>(ws, { url: `${graphPath(graph)}/evidence/${encodeURIComponent(id)}` }, signal),
  history: (ws: string, graph: string, id: string, signal?: AbortSignal) => semanticRequest<{ statementId: string; revisions: Statement[] }>(ws, { url: `${graphPath(graph)}/statements/${encodeURIComponent(id)}/revisions` }, signal),
}
