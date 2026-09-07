import type { Binding, Definition } from './types'
export interface GraphDetail { binding: Binding; definition: Definition }
export interface Items<T> { items: T[]; total?: number }
export interface Proposal {
  operationId: string; subjectId: string; predicateKind: string; predicateKey: string
  valueType: string | null; value: string | null; unit: string | null; targetEntityId: string | null
  validityKind: string; validFrom: string | null; validTo: string | null; evidenceIds: string[]
}
export interface Statement extends Omit<Proposal, 'operationId'> {
  id: string; graphId: string; revision: number; ontologyRevisionId: string
  reviewStatus: string; supportStatus: string; proposedBy: string; createdAt: string
}
export interface Change { id: string; graphId: string; targetStatementId: string; expectedRevision: number; status: string; resultRevision: number | null; content: Proposal }
export interface ConflictMember { kind?: 'STATEMENT' | 'CHANGE_PROPOSAL'; statementId: string; revision: number }
export interface Conflict { id: string; kind: string; status: string; left: ConflictMember; right: ConflictMember; resolution: string }
export interface Snapshot { id: string; sourceKind: string; sourceRef: string; sourceTitle: string; captureVersion: number; textDigest: string; createdAt: string }
export interface SnapshotText { id: string; textDigest: string; text: string }
export interface Evidence { id: string; snapshotId: string; sourceKind: string; sourceRef: string; sourceTitle: string; exactQuote: string; startCodePoint: number; endCodePoint: number; textDigest: string }
export interface Source { sourceKind: string; sourceRef: string; title: string; state: string; snapshotCount: number }
export interface ImportJob { id: string; status: string; snapshotId: string | null; attempts: number; errorMessage: string | null }
export interface GraphResult { nodes: { id: string; typeKey: string; label: string; properties: Statement[] }[]; edges: { statementId: string; revision: number; sourceId: string; targetId: string; predicateKey: string }[]; traceId: string; truncated: boolean }
