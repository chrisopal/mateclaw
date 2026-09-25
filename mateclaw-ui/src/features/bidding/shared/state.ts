export const isCurrentRequest = (workspaceId: string, projectId: string, activeWorkspaceId: string, activeProjectId: string) =>
  workspaceId === activeWorkspaceId && projectId === activeProjectId

export const operationId = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`

export function isConflict(error: unknown): boolean {
  return !!error && typeof error === 'object' && 'response' in error
    && (error as { response?: { status?: number } }).response?.status === 409
}

export function apiUnavailable(error: unknown): boolean {
  return !!error && typeof error === 'object' && 'response' in error
    && (error as { response?: { status?: number } }).response?.status === 404
}
