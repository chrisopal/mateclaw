export interface Ref { kind: string; id: string; version: number; digest: string }
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
export interface AnalysisView { baseline?: { ref: Ref; status: string; payload: AnalysisBaseline }; groups: AnalysisGroup[] }
export interface AnalysisBaseline { schemaVersion: string; taskGroupId: string; analyses: Record<string, AnalysisPayload>; conflicts: unknown[]; [key: string]: unknown }
export interface AnalysisPayload { [key: string]: unknown }
export interface AnalysisGroup { taskGroupId: string; status: string; skills: Record<string, AnalysisPayload>; conflicts: unknown[]; complete: boolean }
export interface Evidence { sourceId: string; version: number; blockId: string; text: string; locator: string; pdfPage?: number }
export interface Command { operationId: string; expected: Ref; action: string; payload: Record<string, unknown> }
export interface CommandResult { ref?: Ref; result?: unknown; taskGroupId?: string; taskIds?: string[]; [key: string]: unknown }
export interface Dashboard { inProgress: number; dueWithin7Days: number; overdueDeadlines: number; unknownDeadlines: number; pendingConfirmation: number; failedTasks: number }
