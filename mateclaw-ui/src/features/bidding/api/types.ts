export interface Ref { kind: string; id: string; version: number | string; digest: string }
export interface Project {
  id: string; workspaceId: string; name: string; lotName: string; ownerId: string
  version: number; stage: string; ref: Ref; capabilities?: ProjectCapabilities
  bindings: Record<string, EmployeeBinding>; selectedRefs: Record<string, Ref>
}
export interface ProjectCapabilities { canApprove: boolean }
export interface EmployeeBinding { agentId?: string | null; skillPins?: unknown[]; available?: boolean; issue?: string }
export interface ProjectPage { items: Project[]; total: number; page: number; pageSize: number }
export interface Capabilities { enabled: boolean; canWrite: boolean; canApprove: boolean }
export interface Member { userId: string | number; username?: string; nickname?: string; role?: string }
export interface Employee { id: string | number; name: string; enabled?: boolean; skills?: string[]; issue?: string }
export interface Source {
  id?: string; sourceId: string; version: number; kind: string; filename: string; digest: string; sha256?: string
  readStatus: string; quality?: string; problems: string[]; blocks?: ReadBlock[]
}
export interface ReadBlock { id: string; pdfPage?: number; locator: string; text: string; kind: string; quality: string }
export interface Task { taskId: string; id?: string; skillId?: string; status: string; attemptCount: number; cycleNo?: number; [key: string]: unknown }
export interface TaskPage { items: Task[]; total: number; page: number; pageSize: number }
export interface Attempt { attemptNo: number; state: string; failureCode?: string; failureMessage?: string; [key: string]: unknown }
export interface TaskDetails extends Task { attempts: Attempt[]; diagnostics?: unknown; input?: unknown; result?: unknown }
export interface BiddingReviewFinding {
  reviewRef: Ref; findingRef: Ref; findingDecisionRef?: Ref; findingDecision?: Record<string, unknown>
  decision: 'OPEN' | 'FIX' | 'DISMISS_WITH_EVIDENCE' | 'DEFER_SUGGESTION' | string
  finding: { id: string; severity?: string; category?: string; description?: string; recommendation?: string; chapterRefs?: Ref[]; requirementRefs?: string[]; evidenceRefs?: Record<string, unknown>[] }
}
export interface BiddingHumanTodo { ref: Ref; title: string; status: string; impactClassification: string; affectsTechnical?: boolean; evidenceRefs?: Record<string, unknown>[] }
export interface BiddingReviewView { manuscriptRef?: Ref; reviewKey?: string; status: string; reason?: string; tasks: Record<string, unknown>[]; findings: BiddingReviewFinding[]; humanTodos: BiddingHumanTodo[]; coverage?: { chapterIds: string[]; requirementIds: string[]; expectedChapterIds: string[]; expectedRequirementIds: string[]; crossChapterReviewed: boolean } }
export interface ExportTemplate { id?: string; name: string; status: 'PREPARED' | 'PREVIEW_ONLY' | 'UNPREPARED' | string; ref?: Ref; formatRef?: Ref; format?: string }
export interface ArtifactMetadata { artifactId: string; filename?: string; mode: 'candidate' | 'preview' | string; status: 'CANDIDATE' | 'PREVIEW' | 'APPROVED' | 'STALE' | string; digest?: string; byteSize?: number; manuscriptRef?: Ref; templateRef?: Ref; formatRef?: Ref; checks?: { previewHeadingLevelsAdjusted?: number; [key: string]: unknown }; [key: string]: unknown }
export interface ArtifactApprovalContext extends ArtifactMetadata { manuscriptRef: Ref; templateRef: Ref; formatRef: Ref; status: 'READY' | 'BLOCKED' | 'STALE' | 'NOT_APPROVABLE' | string; artifactRef?: Ref; reviewRef?: Ref; reviewEvidence?: Record<string, unknown>; reasonCode?: string }
export interface AnalysisView { baseline?: { ref: Ref; status: string; payload: AnalysisBaseline }; groups: AnalysisGroup[] }
export interface AnalysisBaseline { schemaVersion: string; taskGroupId: string; analyses: Record<string, AnalysisPayload>; conflicts: unknown[]; [key: string]: unknown }
export interface AnalysisPayload { [key: string]: unknown }
export interface AnalysisGroup { taskGroupId: string; status: string; skills: Record<string, AnalysisPayload>; conflicts: unknown[]; complete: boolean }
export interface Evidence { id: string; locator: string; text: string; pdfPage?: number }
export interface Command { operationId: string; expected: Ref; action: string; payload: Record<string, unknown> }
export interface CommandResult { ref?: Ref; result?: unknown; taskGroupId?: string; taskIds?: string[]; [key: string]: unknown }
export interface Dashboard { inProgress: number; dueWithin7Days: number; overdueDeadlines: number; unknownDeadlines: number; pendingConfirmation: number; failedTasks: number }

export interface HandoffOption {
  presalesProjectId: string
  releaseId: string
  digest: string
  title: string
  publishedAt?: string
  solutionVersion?: number
  status: 'PUBLISHED'
  available?: boolean
  unavailableReason?: string
}
export interface HandoffSnapshot {
  baseline?: Record<string, unknown>
  solution?: { title?: string; version?: number; sections?: { title?: string; text?: string }[] }
  risksAndUnknowns?: Record<string, unknown>[]
  clarifications?: Record<string, unknown>[]
  historicalClarificationsAvailable?: boolean
  [key: string]: unknown
}
export interface BiddingMaterial {
  ref: Ref
  source: 'WIKI_PAGE' | 'PRESALES_RELEASE' | string
  title: string
  version: number
  digest: string
  validity: string
  applicability: string
  selectedAt?: string
  receivedAt?: string
  solutionVersion?: number
  publishedAt?: string
  content?: HandoffSnapshot
  accessRef?: Record<string, unknown>
}
export interface BiddingMaterialsView { items: BiddingMaterial[] }
export interface OutlineChapter {
  id: string
  parentId: string | null
  order: number
  title: string
  instructions?: string
  mandatoryOutlineRefs?: string[]
  requirementRefs?: string[]
  scoringRefs?: string[]
  materialRefs?: Ref[]
  children?: OutlineChapter[]
  [key: string]: unknown
}
export interface OutlineRevision { ref: Ref; status: string; payload: { chapters?: OutlineChapter[]; unmappedItems?: Record<string, unknown>[]; warnings?: string[] }; inputRefs: Ref[] }
export interface OutlineView { baselineRef?: Ref; editExpectedRef?: Ref; confirmed?: OutlineRevision; candidates: OutlineRevision[]; dispatchTodo?: { status: string; action?: string; reasonCode?: string; baselineRef?: Ref } }
export interface ChapterRevision { ref: Ref; status: string; payload: Record<string, unknown>; inputRefs: Ref[]; headGuard?: Ref }
export interface WritingChapter { chapterId: string; title: string; editExpectedRef?: Ref; selected?: ChapterRevision; candidates: ChapterRevision[]; tasks?: Pick<Task, 'taskId' | 'status' | 'attemptCount'>[] }
export interface ManuscriptChapter { chapterId: string; chapter?: { title?: string; blocks?: Record<string, unknown>[]; [key: string]: unknown }; responses?: unknown[]; citations?: unknown[]; missingMaterials?: unknown[]; unresolvedItems?: unknown[] }
export interface ManuscriptPayload { schemaVersion?: string; status?: string; chapters?: ManuscriptChapter[]; [key: string]: unknown }
export interface WritingView { outlineRef?: Ref; chapters: WritingChapter[]; manuscript?: { ref: Ref; status: string; inputRefs?: Ref[]; payload?: ManuscriptPayload } }

export interface ChangeImpactLabel { ref: Ref; title: string }
export interface ChangeImpact { affectedRefs: Ref[]; unaffectedRefs: Ref[]; unknownRefs: Ref[]; formalBlocked: boolean; refLabels?: ChangeImpactLabel[] }
export interface ChangeImpactConfirmationPayload extends Record<string, unknown> { eventId: string; changedRef: Ref; unchangedRefs: Ref[]; resolutions: Array<{ ref: Ref; decision: string; reason?: string; evidenceRefs?: Ref[] }> }
export interface ChangeImpactConfirmation { ready: boolean; payload?: ChangeImpactConfirmationPayload }
export interface ChangeImpactEvent { eventId: string; changedRef: Ref; replacementRef?: Ref; status: string; impact: ChangeImpact; confirmation?: ChangeImpactConfirmation; refLabels?: ChangeImpactLabel[] }
export interface ChangeImpactView { events: ChangeImpactEvent[]; formalBlocked: boolean }
