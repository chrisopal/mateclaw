import type { Binding, DocumentView } from './types'

export interface LiteralValue { lexicalValue: string; datatypeIri: string; languageTag?: string | null }
export type AssertionKind = 'CLASS_ASSERTION' | 'POSITIVE_OBJECT_PROPERTY' | 'NEGATIVE_OBJECT_PROPERTY' | 'POSITIVE_DATA_PROPERTY' | 'NEGATIVE_DATA_PROPERTY' | 'SAME_INDIVIDUAL' | 'DIFFERENT_INDIVIDUAL'
export interface AssertionPayload { kind: AssertionKind; functionalSyntax: string; signatureIris: string[]; subjectIri?: string | null; predicateIri?: string | null; objectIri?: string | null; literal?: LiteralValue | null; classExpressionFunctionalSyntax?: string | null; relatedIndividualIri?: string | null }
export interface GraphDetail { binding: Binding; document: DocumentView }
export interface Items<T> { items: T[]; total?: number }
export interface Proposal { operationId: string; subjectId: string; assertionText: string; validityKind: string; validFrom: string | null; validTo: string | null; evidenceIds: string[] }
export interface Statement { id: string; graphId: string; revision: number; ontologyRevisionId: string; subjectId: string; assertion: AssertionPayload; validityKind: string; validFrom: string | null; validTo: string | null; reviewStatus: string; supportStatus: string; evidenceIds: string[]; proposedBy: string; createdAt: string }
export interface Change { id: string; graphId: string; targetStatementId: string; expectedRevision: number; status: string; resultRevision: number | null; proposedBy?: string; createdAt?: string; content: Proposal }
export interface ConflictMember { kind?: 'STATEMENT' | 'CHANGE_PROPOSAL'; statementId: string; revision: number }
export interface Conflict { id: string; graphId?: string; kind: string; status: string; left: ConflictMember; right: ConflictMember; resolution: string }
export interface Snapshot { id: string; sourceKind: string; sourceRef: string; sourceTitle: string; captureVersion: number; textDigest: string; createdAt: string }
export interface SnapshotText { id: string; textDigest: string; text: string }
export interface Evidence { id: string; snapshotId: string; sourceKind: string; sourceRef: string; sourceTitle: string; exactQuote: string; startCodePoint: number; endCodePoint: number; textDigest: string }
export interface Source { sourceKind: string; sourceRef: string; title: string; state: string; snapshotCount: number }
export interface ImportJob { id: string; status: string; snapshotId: string | null; attempts: number; errorMessage: string | null }
export type SourceChangeDecision = 'ACKNOWLEDGE' | 'REMODEL' | 'KEEP_HISTORICAL'
export interface SourceChange {
  id: string
  sourceKind: string
  sourceRef: string
  oldSnapshotId: string | null
  newSnapshotId: string | null
  oldDigest: string | null
  newDigest: string
  sourceState: string
  observedGraphVersion: number
  affectedCount: number
  createdAt: string
}
export interface SourceChangeReviewItem {
  id: string
  changeId: string
  graphId: string
  sourceKind: string
  sourceRef: string
  itemKind: string
  itemId: string
  itemRevision: number | null
  oldSnapshotId: string | null
  newSnapshotId: string | null
  oldDigest: string | null
  newDigest: string
  sourceState: string
  reviewState: string
  decision: SourceChangeDecision | null
  reason: string | null
  observedGraphVersion: number | null
  createdAt: string
  reviewedAt: string | null
}
export interface SourceChangeScanResult {
  runId: string
  graphId: string
  graphMutationVersion: number
  changes: SourceChange[]
}
export interface GraphResult { nodes: { id: string; iri: string; assertedTypes: string[]; label: string; properties: Statement[] }[]; edges: { statementId: string; revision: number; sourceId: string; targetId: string; predicateIri: string }[]; traceId: string; truncated: boolean }
export interface SearchResult { facts: Statement[]; traceId: string; truncated: boolean; entityLabels: Record<string, string>; predicateLabels: Record<string, string> }
