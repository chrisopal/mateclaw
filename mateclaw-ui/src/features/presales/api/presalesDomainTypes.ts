/** Accepted raw response contracts. Historical omissions and unknown states are preserved.
 * These shapes describe wire data, not domain approval or model trust.
 */
interface RevisionMetadata {
  [key: string]: unknown
  id?: string
  version?: number
  authorId?: string
  previousId?: string
  createdAt?: string
  createdBy?: string
  updatedAt?: string
  updatedBy?: string
}
interface DomainEntity extends RevisionMetadata {
  id: string
}
export interface PresalesMaterial extends DomainEntity {
  kbId?: string
  graphId?: string
  ontologyRevisionId?: string
  role?: string
  sourceSnapshot?: unknown
}
export interface PresalesRequirement extends DomainEntity {
  title?: string
  description?: string
  text?: string
  originKind?: string
  priority?: string
  scope?: string
  kind?: string
  graphId?: string
  statementId?: string
  statementRevision?: string | number
  evidenceIds?: string[]
  sourceRefs?: string[]
  proposedByTaskId?: string
  agentId?: string
  authority?: string
  customerConfirmationStatus?: string
}
export interface PresalesClarification extends DomainEntity {
  question?: string
  requirementId?: string
  impact?: string
  ownerId?: string
  answer?: string
  answerSourceId?: string
  status?: string
  answeredBy?: string
  answeredAt?: string
  sourceRefs?: string[]
  proposedByTaskId?: string
  agentId?: string
  authority?: string
}
export interface PresalesBaselineReference extends RevisionMetadata {
  requirementId?: string
  requirementVersion?: number
  scope?: string
  graphId?: string
  statementId?: string
  statementRevision?: string | number
  ontologyRevisionId?: string | null
  evidenceIds?: (string | null)[] | null
  assertion?: unknown
  sources?: unknown[]
}
export interface PresalesBaseline extends DomainEntity {
  approvedBy?: string
  projectVersion?: number
  reason?: string
  customerConfirmationStatus?: string
  references?: PresalesBaselineReference[]
}
export interface PresalesFitGap extends DomainEntity {
  requirementId?: string
  status?: string
  reason?: string
  evidenceText?: string
  evidenceIds?: string[]
  sourceRefs?: string[]
  graphId?: string
  productVersion?: string
  authority?: string
}
export interface PresalesSolutionSection extends RevisionMetadata {
  evidenceRefs?: string[]
  title?: string
  text?: string
  requirementRefs?: string[]
  sourceRefs?: string[]
}
export interface PresalesRequirementResponse extends RevisionMetadata {
  requirementId?: string
  status?: string
  reason?: string
  evidenceIds?: string[]
  sourceRefs?: string[]
}
export interface PresalesCoverage {
  [key: string]: unknown
  applicable: boolean
  handledIn: number
  totalIn: number
  percentage?: number | null
  responses?: PresalesRequirementResponse[]
}
export interface PresalesSolutionDraft extends RevisionMetadata {
  status?: string
  title?: string
  baselineId?: string
  baselineVersion?: number
  sourceSolutionId?: string
  provisional?: boolean
  fitGapRefs?: string[]
  sourceRefs?: string[]
  sections?: PresalesSolutionSection[]
  requirementResponses?: PresalesRequirementResponse[]
  coverage?: PresalesCoverage
  presentation?: PresalesPresentation
  authority?: string
}
export interface PresalesSolutionRevision extends PresalesSolutionDraft {
  id: string
}
export interface PresalesReviewIssue extends RevisionMetadata {
  description?: string
  text?: string
  severity?: string
  status?: string
}
export interface PresalesReview extends DomainEntity {
  solutionId?: string
  kind?: string
  summary?: string
  authority?: string
  issues?: PresalesReviewIssue[]
}
/** Manifest metadata only; the file endpoint still returns exact binary bytes. */
export interface PresalesArtifact {
  [key: string]: unknown
  filename: string
  sha256?: string
  size?: number
}
export interface PresalesPresentation extends RevisionMetadata {
  artifactId: string
  skill: string
  skillVersion: string
  pageCount: number
  adapter?: string
  sha256?: string
  skillSha256?: string
  engineSha256?: string
  engineTreeSha256?: string
  inputSha256?: string
  qualityReportSha256?: string
  slides?: (PresalesArtifact & { title?: string })[]
}
export interface PresalesRelease extends DomainEntity {
  solutionId?: string
  baselineId?: string
  reviewId?: string
  status?: string
  purpose?: string
  templateVersion?: string
  files?: PresalesArtifact[]
  approvedBy?: string
  approvalReason?: string
  publishedBy?: string
  publishedAt?: string
  handoffSnapshot?: unknown
}
export interface PresalesContextSnapshot extends PresalesRecord {
  workspaceId?: string
  caseRef?: string
  actorId?: string
  projectVersion?: number
  schemaVersion?: number
  taskGoal?: string
  skillVersion?: string
  targetSolutionId?: string
  truncated?: boolean
  needsHumanReview?: boolean
  sources?: unknown[]
  operational_record?: unknown
}
export interface PresalesGenerationResult {
  [key: string]: unknown
  items?: PresalesRecord[]
  capabilityMaps?: PresalesRecord[]
  cases?: PresalesRecord[]
  solution?: PresalesSolutionDraft
  solutionDraft?: PresalesSolutionDraft
  review?: PresalesRecord
  reviewDraft?: PresalesRecord
  // Raw model envelope values remain opaque; server validation can coerce historical JSON.
  schemaVersion?: unknown
  needsHumanReview?: unknown
  unknowns?: unknown
  assumptions?: unknown
  warnings?: unknown
}
export interface PresalesGenerationTask extends DomainEntity {
  status: string
  operationId?: string
  requestHash?: string
  skill?: string
  taskGoal?: string
  agentId?: string
  agentName?: string
  queueState?: string
  queuedAt?: string
  finishedAt?: string
  runId?: string
  conversationId?: string
  modelConfigId?: string
  configDigest?: string
  skillName?: string
  skillDigest?: string
  presentationDigest?: string
  needsHumanReview?: boolean
  contextSnapshot?: PresalesContextSnapshot
  result?: PresalesGenerationResult
  rejectedOutput?: unknown
  error?: string
}
export type PresalesTask = PresalesGenerationTask
export interface PresalesHandoff {
  [key: string]: unknown
  schemaVersion: 1
  engagementId: string
  caseRef: string
  workspaceId: string
  baseline: PresalesBaseline
  solution: PresalesSolutionRevision
  release: PresalesRelease
  fitGaps: PresalesFitGap[]
  clarifications: PresalesClarification[]
  risksAndUnknowns: (PresalesFitGap | PresalesRequirementResponse)[]
  customerConfirmationStatus: string
  accessPolicy: string
  releaseId?: string
  historicalClarificationsAvailable?: boolean
  materials?: PresalesMaterial[]
  // Publication appends raw clarification refs to frozen baseline objects.
  sourceRefs?: unknown[]
}
export interface PresalesEntity extends PresalesRecord {
  id: string
}

// Heterogeneous historical records omit fields; opaque source snapshots remain unknown.
export interface PresalesRecord {
  id?: string
  [key: string]: unknown
  authorId?: string
  previousId?: string
  version?: number
  createdBy?: string
  createdAt?: string
  updatedBy?: string
  updatedAt?: string
  enabled?: boolean
  artifactId?: string
  files?: PresalesArtifact[]
  name?: string
  title?: string
  text?: string
  status?: string
  role?: string
  description?: string
  originKind?: string
  priority?: string
  scope?: string
  authority?: string
  customerConfirmationStatus?: string
  userId?: string
  nickname?: string
  username?: string
  kbId?: string
  graphId?: string
  ontologyRevisionId?: string
  revision?: number
  label?: string
  requirementId?: string
  statementId?: string
  statementRevision?: string | number
  proposedByTaskId?: string
  question?: string
  impact?: string
  ownerId?: string
  answer?: string
  answerSourceId?: string
  reason?: string
  evidenceText?: string
  productVersion?: string
  baselineId?: string
  solutionId?: string
  purpose?: string
  summary?: string
  severity?: string
  operationId?: string
  skill?: string
  conversationId?: string
  agentId?: string
  agentName?: string
  runId?: string
  queueState?: string
  error?: string
  requirementRefs?: string[]
  evidenceIds?: string[]
  evidenceRefs?: string[]
  sourceRefs?: string[]
  sections?: PresalesRecord[]
  references?: PresalesBaselineReference[]
  requirementResponses?: PresalesRecord[]
  issues?: PresalesRecord[]
  sourceSnapshot?: unknown
  presentation?: PresalesPresentation
  coverage?: PresalesCoverage
  contextSnapshot?: PresalesContextSnapshot
  result?: PresalesGenerationResult
}

export interface PresalesEditorForm extends PresalesRecord {
  customer?: string
  industry?: string
  goal?: string
}
