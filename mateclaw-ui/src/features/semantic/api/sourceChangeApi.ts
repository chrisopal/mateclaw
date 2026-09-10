import { semanticRequest } from './ontologyApi'
import { graphPath } from './graphQueryApi'
import type { SourceChangeReviewItem, SourceChangeScanResult, SourceChangeDecision } from './workbenchTypes'

export interface SourceChangeScanRequest {
  operationId: string
  expectedGraphVersion: number
  sourceKind?: string
  sourceRef?: string
}

export interface SourceChangeDecisionRequest {
  operationId: string
  expectedObservedDigest: string
  expectedGraphVersion: number
  decision: SourceChangeDecision
  reason: string
}

/** Graph source review endpoints. Mutation callers must supply their own idempotency key and CAS version. */
export const sourceChangeApi = {
  scan: (ws: string, graph: string, body: SourceChangeScanRequest, signal?: AbortSignal) =>
    semanticRequest<SourceChangeScanResult>(ws, {
      url: `${graphPath(graph)}/source-changes/scan`,
      method: 'POST',
      data: body,
    }, signal),
  pending: (ws: string, graph: string, limit = 100, signal?: AbortSignal) =>
    semanticRequest<SourceChangeReviewItem[]>(ws, {
      url: `${graphPath(graph)}/source-changes`,
      params: { limit },
    }, signal),
  items: (ws: string, graph: string, changeId: string, signal?: AbortSignal) =>
    semanticRequest<SourceChangeReviewItem[]>(ws, {
      url: `${graphPath(graph)}/source-changes/${encodeURIComponent(changeId)}/items`,
    }, signal),
  decide: (ws: string, graph: string, itemId: string, body: SourceChangeDecisionRequest, signal?: AbortSignal) =>
    semanticRequest<SourceChangeReviewItem>(ws, {
      url: `${graphPath(graph)}/source-changes/items/${encodeURIComponent(itemId)}/decision`,
      method: 'POST',
      data: body,
    }, signal),
}
