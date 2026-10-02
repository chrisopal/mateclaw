export interface WorkspaceLoopback {
  baseURL: string
  readonly received: { scope?: string; contentType?: string; body: string } | undefined
  reset(): void
  waitForSlowRequest(): Promise<void>
  close(): Promise<void>
}
export function startWorkspaceLoopback(): Promise<WorkspaceLoopback>
