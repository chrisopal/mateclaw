export interface WorkspaceLoopback {
  baseURL: string
  readonly received: { scope?: string; contentType?: string; body: string } | undefined
  reset(): void
  waitForSlowRequest(): Promise<void>
  waitForSlowDisconnect(): Promise<void>
  close(): Promise<void>
}
export function startWorkspaceLoopback(): Promise<WorkspaceLoopback>
