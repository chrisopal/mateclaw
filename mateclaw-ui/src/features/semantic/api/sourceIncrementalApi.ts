import { semanticRequest } from './ontologyApi'
import type { ModelingTask } from './modelingTaskApi'
import type { Change, SourceChangeReviewItem, Statement } from './workbenchTypes'

export interface FactRevisionPreview {
  item: SourceChangeReviewItem
  statement: Statement
  original: { id: string; digest: string; text: string }
  observed: { id: string; digest: string; text: string }
}
export interface FactRevisionInput {
  expectedObservedDigest: string
  expectedRevision: number
  assertionText: string
  validityKind: string
  validFrom: string | null
  validTo: string | null
  exactQuote: string
  startCodePoint: number
  endCodePoint: number
}
const factPath = (graph: string, item: string) => `/semantic/graphs/${encodeURIComponent(graph)}/source-changes/items/${encodeURIComponent(item)}/fact-revision`
export const sourceIncrementalApi = {
  modelTask: (ws: string, ontology: string, review: string, data: { expectedObservedDigest: string; goal?: string }, signal?: AbortSignal) =>
    semanticRequest<ModelingTask>(ws, { url: `/semantic/ontologies/${encodeURIComponent(ontology)}/source-reviews/${encodeURIComponent(review)}/modeling-task`, method: 'POST', data }, signal),
  factPreview: (ws: string, graph: string, item: string, signal?: AbortSignal) =>
    semanticRequest<FactRevisionPreview>(ws, { url: factPath(graph, item) }, signal),
  reviseFact: (ws: string, graph: string, item: string, data: FactRevisionInput, signal?: AbortSignal) =>
    semanticRequest<Change>(ws, { url: factPath(graph, item), method: 'POST', data }, signal),
}
