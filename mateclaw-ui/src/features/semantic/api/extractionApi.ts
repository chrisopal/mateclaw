import { semanticRequest } from './ontologyApi'
import { graphPath } from './graphQueryApi'
import type { Items } from './workbenchTypes'
export interface ExtractionQuote { startCodePoint: number; endCodePoint: number; exactQuote: string }
export interface ExtractionCapabilities { enabled: boolean; models: { id: string; name: string }[]; ontologyRevisionId: string }
export interface ExtractionTask {
  id: string; status: string; version: number; graphId: string; snapshotId: string; ontologyRevisionId: string
  sourceRef: string; sourceTitle: string; sourceText: string; modelName: string; attempts: number
  totalChunks: number; completedChunks: number; traceId: string; errorCode: string | null; errorMessage: string | null
  explanation: string | null; createdAt: string; updatedAt: string
}
export interface ExtractionSuggestion {
  id: string; version: number; status: string; subjectTypeKey: string; subjectName: string; subjectId: string | null
  predicateKind: string; predicateKey: string; valueType: string | null; value: string | null; unit: string | null
  targetTypeKey: string | null; targetName: string | null; targetEntityId: string | null
  validityKind: string; validFrom: string | null; validTo: string | null
  quotes: ExtractionQuote[]; diagnostics: string[]; statementId: string | null; pendingOperationId?: string | null
}
export type SuggestionEdit = Omit<ExtractionSuggestion, 'id' | 'version' | 'diagnostics' | 'statementId' | 'pendingOperationId'> & { expectedVersion: number; operationId: string }
const base = (g: string) => `${graphPath(g)}/extraction-tasks`
const taskPath = (g: string, id: string) => `${base(g)}/${encodeURIComponent(id)}`
const suggestionPath = (g: string, id: string) => `${graphPath(g)}/suggestions/${encodeURIComponent(id)}`
export const extractionApi = {
  capabilities: (ws: string, g: string, signal?: AbortSignal) => semanticRequest<ExtractionCapabilities>(ws, { url: `${graphPath(g)}/extraction-capabilities` }, signal),
  list: (ws: string, g: string, page = 1, signal?: AbortSignal) => semanticRequest<Items<ExtractionTask>>(ws, { url: base(g), params: { page, pageSize: 20 } }, signal),
  start: (ws: string, g: string, data: { sourceRef: string; modelConfigId: string; operationId: string }, signal?: AbortSignal) => semanticRequest<ExtractionTask>(ws, { url: base(g), method: 'POST', data }, signal),
  read: (ws: string, g: string, id: string, signal?: AbortSignal) => semanticRequest<ExtractionTask>(ws, { url: taskPath(g, id) }, signal),
  cancel: (ws: string, g: string, id: string, operationId: string, signal?: AbortSignal) => semanticRequest<ExtractionTask>(ws, { url: `${taskPath(g, id)}/cancel`, method: 'POST', data: { operationId } }, signal),
  retry: (ws: string, g: string, id: string, operationId: string, signal?: AbortSignal) => semanticRequest<ExtractionTask>(ws, { url: `${taskPath(g, id)}/retry`, method: 'POST', data: { operationId } }, signal),
  suggestions: (ws: string, g: string, id: string, page = 1, signal?: AbortSignal) => semanticRequest<Items<ExtractionSuggestion>>(ws, { url: `${taskPath(g, id)}/suggestions`, params: { page, pageSize: 100 } }, signal),
  edit: (ws: string, g: string, id: string, data: SuggestionEdit, signal?: AbortSignal) => semanticRequest<ExtractionSuggestion>(ws, { url: suggestionPath(g, id), method: 'PATCH', data }, signal),
  submit: (ws: string, g: string, id: string, data: { expectedVersion: number; operationId: string }, signal?: AbortSignal) => semanticRequest<{ statementId: string; revision: number }>(ws, { url: `${suggestionPath(g, id)}/submit`, method: 'POST', data }, signal),
}
