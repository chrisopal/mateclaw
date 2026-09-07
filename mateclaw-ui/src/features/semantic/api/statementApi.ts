import { semanticRequest } from './ontologyApi'
import { graphPath } from './graphQueryApi'
import type { Change, Conflict, Items, Proposal, Statement } from './workbenchTypes'
const path = (g: string, id: string) => `${graphPath(g)}/statements/${encodeURIComponent(id)}`
export const statementApi = {
  list: (ws: string, g: string, view: string, page = 1, signal?: AbortSignal) => semanticRequest<Items<Statement>>(ws, { url: `${graphPath(g)}/statements`, params: { view, page, pageSize: 100 } }, signal),
  propose: (ws: string, g: string, data: Proposal, signal?: AbortSignal) => semanticRequest<Statement>(ws, { url: `${graphPath(g)}/statements`, method: 'POST', data }, signal),
  change: (ws: string, g: string, target: Statement, content: Proposal, signal?: AbortSignal) => semanticRequest<Change>(ws, { url: `${path(g, target.id)}/changes`, method: 'POST', data: { expectedRevision: target.revision, operationId: crypto.randomUUID(), content } }, signal),
  review: (ws: string, g: string, statement: Statement, action: string, reason: string, signal?: AbortSignal) => semanticRequest<Statement>(ws, { url: `${path(g, statement.id)}/review`, method: 'POST', data: { expectedRevision: statement.revision, action, reason, operationId: crypto.randomUUID() } }, signal),
  changes: (ws: string, g: string, page = 1, signal?: AbortSignal) => semanticRequest<Items<Change>>(ws, { url: `${graphPath(g)}/changes`, params: { page, pageSize: 100 } }, signal),
  reviewChange: (ws: string, g: string, change: Change, action: string, reason: string, signal?: AbortSignal) => semanticRequest<Change>(ws, { url: `${graphPath(g)}/changes/${encodeURIComponent(change.id)}/review`, method: 'POST', data: { expectedRevision: change.expectedRevision, action, reason, operationId: crypto.randomUUID() } }, signal),
  conflicts: (ws: string, g: string, page = 1, signal?: AbortSignal) => semanticRequest<Items<Conflict>>(ws, { url: `${graphPath(g)}/conflicts`, params: { page, pageSize: 100 } }, signal),
  resolve: (ws: string, g: string, conflict: Conflict, winnerStatementId: string, reason: string, signal?: AbortSignal) => semanticRequest<Conflict>(ws, { url: `${graphPath(g)}/conflicts/${encodeURIComponent(conflict.id)}/resolve`, method: 'POST', data: { winnerStatementId, expectedMembers: [conflict.left, conflict.right], reason, operationId: crypto.randomUUID() } }, signal),
}
