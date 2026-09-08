export interface EntityType {
  key: string
  label: string
  description: string
  aliases?: string[]
  deprecated?: boolean
}
export type Multiplicity = 'SINGLE' | 'MULTI'
export type ValueType = 'TEXT' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'INSTANT'
export interface Property extends EntityType {
  ownerTypeKey: string
  valueType: ValueType
  multiplicity: Multiplicity
  fixedUnit: string | null
  constraints?: PropertyConstraints
}
export interface Relation extends EntityType {
  sourceTypeKey: string
  targetTypeKey: string
  multiplicity: Multiplicity
}
export interface Definition {
  /** Missing on legacy format-1 revisions; new writes use format 2. */
  definitionFormatVersion?: number
  types: EntityType[]
  properties: Property[]
  relations: Relation[]
}
export type DefinitionCategory = 'types' | 'properties' | 'relations'
export interface PropertyConstraints {
  allowedValues?: string[] | null
  minimum?: string | null
  maximum?: string | null
}
export interface Metadata {
  name: string
  description: string
}
export interface Ontology extends Metadata {
  id: string
  workspaceId: string
  latestVersion: number | null
  latestRevisionId: string | null
  hasDraft: boolean
  updatedAt: string
}
export interface Draft extends Metadata {
  id: string
  ontologyId: string
  baseRevisionId: string | null
  version: number
  draftVersion: number
  definition: Definition
}
export interface Revision extends Metadata {
  id: string
  ontologyId: string
  version: number
  definition: Definition
  availableForNewBindings: boolean
  publishedAt: string
  publishedBy: string
  publicationNote: string
  baseRevisionId: string | null
}
export interface Violation {
  code: string
  path: string
  message: string
  severity?: string
}
export interface ValidationReport {
  draftVersion: number
  valid: boolean
  violations: Violation[]
}
export interface Diff {
  fromRevisionId: string | null
  toRevisionId: string
  changes: { kind: string; category: string; key: string; before: unknown; after: unknown }[]
  definitionChangeClass?: string
  termChanges?: { kind: string; key: string; definitionChangeClass: string; reasons: string[] }[]
}
export interface Page {
  items: Ontology[]
  total: number
  page: number
  pageSize: number
}
export interface SaveDraft extends Metadata {
  expectedDraftVersion: number
  definition: Definition
}
export interface PublishDraft {
  expectedDraftVersion: number
  operationId: string
  note: string
}

export interface Binding {
  graphId: string
  workspaceId: string
  knowledgeBaseId: string
  ontologyRevisionId: string
  ontologyVersion: number
  enabled: boolean
  graphVersion: number
  empty: boolean
  updatedAt: string
}

export interface OntologyPackage {
  packageFormatVersion: 1
  name: string
  description: string
  source?: { ontologyName: string; version: number }
  definition: Definition
}

export interface PackagePreview {
  digest: string
  name: string
  typeCount: number
  predicateCount: number
  violations: Violation[]
}

export interface PackageImportResult {
  operationId: string
  ontologyId: string
  draft: Draft
}

export interface OntologyUsageItem {
  graphId: string
  kbId: string
  kbName: string
  ontologyRevisionId: string
  ontologyVersion: number
  enabled: boolean
  graphVersion: number
}

export interface OntologyUsagePage {
  items: OntologyUsageItem[]
  total: number
  page: number
  pageSize: number
}

export type ImpactDiagnosticKind = 'ENTITY' | 'STATEMENT' | 'CHANGE_PROPOSAL'
export interface ImpactDiagnostic {
  kind: ImpactDiagnosticKind
  id: string
  revision?: number
  code: string
  message: string
  termKey?: string
}

export interface ImpactReport {
  graphId: string
  sourceRevisionId: string
  targetRevisionId?: string
  targetDraftVersion?: number
  definitionDigest: string
  graphVersion: number
  scannedAt: string
  definitionChangeClass: string
  dataConformance: string
  scannedEntities: number
  scannedStatements: number
  scannedProposals: number
  affectedEntities: number
  affectedStatements: number
  affectedProposals: number
  detailsTruncated: boolean
  diagnostics: ImpactDiagnostic[]
}
export interface BindRequest {
  action: 'ENABLE' | 'DISABLE' | 'REBIND'
  revisionId?: string
  expectedGraphVersion?: number
}
export interface SemanticEntity {
  id: string
  graphId: string
  typeKey: string
  displayName: string
  status: string
  createdAt: string
}
export interface EntityPage { items: SemanticEntity[] }
