import { semanticRequest } from './ontologyApi'
import { graphPath } from './graphQueryApi'
import type { Evidence, ImportJob, Items, Snapshot, SnapshotText, Source } from './workbenchTypes'
export const sourceApi = {
  materials: (ws: string, kb: string, signal?: AbortSignal) => semanticRequest<{ id: string; title: string }[]>(ws, { url: `/wiki/knowledge-bases/${encodeURIComponent(kb)}/raw` }, signal),
  sources: (ws: string, graph: string, signal?: AbortSignal) => semanticRequest<Items<Source>>(ws, { url: `${graphPath(graph)}/sources` }, signal),
  snapshots: (ws: string, graph: string, signal?: AbortSignal) => semanticRequest<Items<Snapshot>>(ws, { url: `${graphPath(graph)}/snapshots` }, signal),
  capture: (ws: string, graph: string, sourceRef: string, signal?: AbortSignal) => semanticRequest<ImportJob>(ws, { url: `${graphPath(graph)}/imports`, method: 'POST', data: { sourceKind: 'WIKI_RAW', sourceRef, operationId: crypto.randomUUID() } }, signal),
  job: (ws: string, graph: string, id: string, signal?: AbortSignal) => semanticRequest<ImportJob>(ws, { url: `${graphPath(graph)}/imports/${encodeURIComponent(id)}` }, signal),
  retry: (ws: string, graph: string, id: string, signal?: AbortSignal) => semanticRequest<ImportJob>(ws, { url: `${graphPath(graph)}/imports/${encodeURIComponent(id)}/retry`, method: 'POST', data: { operationId: crypto.randomUUID() } }, signal),
  text: (ws: string, graph: string, id: string, signal?: AbortSignal) => semanticRequest<SnapshotText>(ws, { url: `${graphPath(graph)}/snapshots/${encodeURIComponent(id)}/text` }, signal),
  evidence: (ws: string, graph: string, id: string, range: { startCodePoint: number; endCodePoint: number; exactQuote: string }, signal?: AbortSignal) => semanticRequest<Evidence>(ws, { url: `${graphPath(graph)}/snapshots/${encodeURIComponent(id)}/evidence`, method: 'POST', data: { ...range, operationId: crypto.randomUUID() } }, signal),
  govern: (ws: string, graph: string, source: Pick<Source, 'sourceKind' | 'sourceRef'>, reason: string, snapshotId?: string, signal?: AbortSignal) => semanticRequest(ws, { url: `${graphPath(graph)}/${snapshotId ? `snapshots/${encodeURIComponent(snapshotId)}/exclude` : 'sources/withdraw'}`, method: 'POST', data: { ...source, reason, operationId: crypto.randomUUID() } }, signal),
}
