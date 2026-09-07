export interface EntityType {
  key: string
  label: string
  description: string
}
export type Multiplicity = 'SINGLE' | 'MULTI'
export type ValueType = 'TEXT' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'INSTANT'
export interface Property extends EntityType {
  ownerTypeKey: string
  valueType: ValueType
  multiplicity: Multiplicity
  fixedUnit: string | null
}
export interface Relation extends EntityType {
  sourceTypeKey: string
  targetTypeKey: string
  multiplicity: Multiplicity
}
export interface Definition {
  types: EntityType[]
  properties: Property[]
  relations: Relation[]
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
