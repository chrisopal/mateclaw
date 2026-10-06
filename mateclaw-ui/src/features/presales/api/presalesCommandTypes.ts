/** Raw write contracts, not authorization or domain validation.
 * Optional fields keep historical partial requests and the server's validation order.
 * Unrecognized JSON and status text retain their exact wire representation.
 */
interface CommandObject {
  [key: string]: unknown
  id?: string
}
export interface PresalesProjectUpdatePayload {
  [key: string]: unknown
  name?: string
  customer?: string
  ownerId?: string
  agentId?: string
  industry?: string
  goal?: string
}
export interface PresalesMaterialPayload extends CommandObject {
  kbId?: string
  graphId?: string
  role?: string
}
export interface PresalesRequirementPayload extends CommandObject {
  title?: string
  description?: string
  originKind?: string
  priority?: string
  scope?: string
  statementId?: string
  statementRevision?: string | number
  graphId?: string
  proposedByTaskId?: string
}
export interface PresalesClarificationPayload extends CommandObject {
  question?: string
  requirementId?: string
  impact?: string
  ownerId?: string
  answer?: string
  answerSourceId?: string
  status?: string
}
export interface PresalesTaskDraftPayload extends CommandObject {
  status?: string
  skill?: string
  // Model data is still unknown until the server validates its pinned task context.
  result?: unknown
  contextSnapshot?: unknown
}
export interface PresalesContextPayload extends CommandObject {
  title?: string
  text?: string
  originKind?: string
  sourceRefs?: readonly string[]
}
export interface PresalesReviewIssuePayload extends CommandObject {
  description?: string
  severity?: string
  status?: string
  text?: string
}
export interface PresalesReviewPayload extends CommandObject {
  solutionId?: string
  summary?: string
  issues?: readonly PresalesReviewIssuePayload[]
}
export interface PresalesReleasePayload extends CommandObject {
  solutionId?: string
  purpose?: string
}
export interface PresalesFitGapPayload extends CommandObject {
  requirementId?: string
  status?: string
  reason?: string
  productVersion?: string
  graphId?: string
  evidenceIds?: readonly string[]
}
export interface PresalesSolutionSectionPayload extends CommandObject {
  title?: string
  text?: string
  requirementRefs?: readonly string[]
  sourceRefs?: readonly string[]
}
export interface PresalesRequirementResponsePayload extends CommandObject {
  requirementId?: string
  status?: string
  reason?: string
  evidenceIds?: readonly string[]
}
export interface PresalesSolutionPayload extends CommandObject {
  title?: string
  baselineId?: string
  baselineVersion?: number
  presentation?: unknown
  sourceRefs?: readonly string[]
  sections?: readonly PresalesSolutionSectionPayload[]
  requirementResponses?: readonly PresalesRequirementResponsePayload[]
}
export interface PresalesCommandPayloads {
  UPDATE_PROJECT: PresalesProjectUpdatePayload
  ARCHIVE: Record<string, unknown>
  BIND_MATERIAL: PresalesMaterialPayload
  SAVE_REQUIREMENT: PresalesRequirementPayload
  SAVE_CLARIFICATION: PresalesClarificationPayload
  UNBIND_MATERIAL: { [key: string]: unknown; id?: string }
  CANCEL_AI_TASK: { [key: string]: unknown; taskId?: string }
  SAVE_AI_TASK: PresalesTaskDraftPayload
  SAVE_CONTEXT: PresalesContextPayload
  SAVE_REVIEW: PresalesReviewPayload
  CREATE_RELEASE: PresalesReleasePayload
  APPROVE_RELEASE: { [key: string]: unknown; releaseId?: string; reason?: string }
  PUBLISH_RELEASE: { [key: string]: unknown; releaseId?: string }
  APPROVE_BASELINE: { [key: string]: unknown; reason?: string }
  SAVE_FIT_GAP: PresalesFitGapPayload
  SAVE_SOLUTION: PresalesSolutionPayload
}
export type PresalesCommandAction = keyof PresalesCommandPayloads
export type PresalesCommandIntent = {
  [Action in PresalesCommandAction]: { action: Action; payload: PresalesCommandPayloads[Action] }
}[PresalesCommandAction]
