export type OntologyDocumentSyntax = 'FUNCTIONAL' | 'RDF_XML'

export interface LockedImport {
  requestedIri: string
  resolvedOntologyIri: string
  versionIri?: string | null
  syntax: OntologyDocumentSyntax
  documentText: string
  contentDigest: string
  artifactId: string
}
export interface BusinessPolicyRule { classIri: string; predicateIri: string; required: boolean; unit: string | null; allowedLexicalValues: string[]; singleValue?: boolean }
export interface BusinessPolicySet { version: string; rules: BusinessPolicyRule[] }
export interface DocumentInput { modelSchema: 'owl-document-v1'; syntax: OntologyDocumentSyntax; documentText: string; imports: LockedImport[]; policy: BusinessPolicySet }
export interface OntologyAnnotationDescriptor { propertyIri: string; valueRendering: string; nestedAnnotations: OntologyAnnotationDescriptor[] }
export interface AxiomDescriptor { axiomId: string; axiomType: string; rendering: string; signatureIris: string[]; annotations: OntologyAnnotationDescriptor[]; logical: boolean }
export type OntologySourceOrigin = 'EXTRACTED' | 'EXPERT' | 'INFERRED'
export interface OntologySourceBinding { id: string; revisionId: string; axiomId: string; sourceSnapshotId: string; knowledgeBaseId: string; sourceRef: string; sourceDigest: string; exactQuote: string; startCodePoint: number; endCodePoint: number; origin: OntologySourceOrigin; reviewState: string; currentSourceState: string }
export interface OntologySourceReview { id: string; bindingId: string; observedDigest: string; sourceState: string; reviewState: string; decision: string | null; reason: string | null; observedSnapshotId?: string | null }
export interface DocumentView { source: DocumentInput; ontologyIri: string; versionIri: string | null; documentDigest: string; importLockDigest: string; axioms: AxiomDescriptor[] }
export interface Metadata { name: string; description: string }
export interface Ontology extends Metadata { id: string; workspaceId: string; latestVersion: number | null; latestRevisionId: string | null; hasDraft: boolean; updatedAt: string }
export interface Draft extends Metadata { id: string; ontologyId: string; baseRevisionId: string | null; version: number; draftVersion: number; document: DocumentView }
export interface Revision extends Metadata { id: string; ontologyId: string; version: number; document: DocumentView; availableForNewBindings: boolean; publishedAt: string; publishedBy: string; publicationNote: string; baseRevisionId: string | null }
export interface SaveDraft extends Metadata { expectedDraftVersion: number; document: DocumentInput; operationId: string }
export interface AxiomEdit { kind: 'ADD' | 'REMOVE'; axiomId?: string; functionalSyntax?: string }
export interface EditDraft { expectedDraftVersion: number; changes: AxiomEdit[]; operationId: string }
export interface PublishDraft { expectedDraftVersion: number; operationId: string; note: string }
export interface Violation { code: string; path: string; message: string; severity?: string }
export interface ValidationReport { draftVersion: number; valid: boolean; violations: Violation[]; profile?: string; reasoningStatus?: string }
export interface Diff { fromRevisionId: string | null; toRevisionId: string; changes: { kind: string; category: string; key: string; before: unknown; after: unknown }[]; definitionChangeClass?: string; termChanges?: { kind: string; key: string; definitionChangeClass: string; reasons: string[] }[] }
export interface OntologyPackage { packageFormatVersion: 2; name: string; description: string; source?: { ontologyName: string; version: number }; document: DocumentInput }
export interface PackagePreview { digest: string; name: string; axiomCount: number; importCount: number; typeCount?: number; predicateCount?: number; violations: Violation[] }
export interface PackageImportResult { operationId: string; ontologyId: string; draft: Draft }
export interface Binding { graphId: string; workspaceId: string; knowledgeBaseId: string; ontologyRevisionId: string; ontologyVersion: number; enabled: boolean; graphVersion: number; empty: boolean; updatedAt: string }
export interface SemanticEntity { id: string; graphId: string; iri: string; assertedTypes: string[]; displayName: string; status: string; createdAt: string }
export interface EntityPage { items: SemanticEntity[] }
export type ImpactDiagnosticKind = 'ENTITY' | 'STATEMENT' | 'CHANGE_PROPOSAL'
export interface ImpactDiagnostic { kind: ImpactDiagnosticKind; id: string; revision?: number; code: string; message: string; termKey?: string }
export interface ImpactReport { graphId: string; sourceRevisionId: string; targetRevisionId?: string; targetDraftVersion?: number; definitionDigest: string; graphVersion: number; scannedAt: string; definitionChangeClass: string; dataConformance: string; scannedEntities: number; scannedStatements: number; scannedProposals: number; affectedEntities: number; affectedStatements: number; affectedProposals: number; detailsTruncated: boolean; diagnostics: ImpactDiagnostic[] }
export type BindRequest = { action: 'ENABLE' | 'DISABLE' | 'REBIND'; revisionId?: string; expectedGraphVersion?: number }
export interface OntologyUsageItem { graphId: string; kbId: string; kbName: string; ontologyRevisionId: string; ontologyVersion: number; enabled: boolean; graphVersion: number }
export interface OntologyUsagePage { items: OntologyUsageItem[]; total: number; page: number; pageSize: number }
export interface Page { items: Ontology[]; total: number; page: number; pageSize: number }

export interface OntologySourceSnapshot { id: string; knowledgeBaseId: string; sourceRef: string; sourceTitle: string; sourceText: string; sourceDigest: string; capturedAt: string }
