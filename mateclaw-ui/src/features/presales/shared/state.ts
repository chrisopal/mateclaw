export function presalesError(error: unknown): { conflict: boolean; message: string } {
  const value = error as { message?: string; response?: { status?: number; data?: { msg?: string; message?: string; code?: string; data?: { code?: string } } } }
  const code = value?.response?.data?.data?.code || value?.response?.data?.code
  return { conflict: code ? ['VERSION_CONFLICT', 'OPERATION_CONFLICT'].includes(code) : value?.response?.status === 409, message: value?.response?.data?.msg || value?.response?.data?.message || value?.message || 'Request failed' }
}
export function coverageLabel(inScope: number, responded: number): string {
  return inScope === 0 ? '—' : `${responded}/${inScope}`
}
/** A response from a previous workspace or route must never replace current data. */
export function isCurrentRequest(capturedWorkspace: string, currentWorkspace: string | null, capturedId: string, currentId: string): boolean {
  return capturedWorkspace === currentWorkspace && capturedId === currentId
}
/** Keep the same receipt key when a timeout leaves a mutation outcome uncertain. */
export function operationReceipt() {
  let previousInput = '', previousId = ''
  return (input: unknown): string => {
    const serialized = JSON.stringify(input)
    if (serialized !== previousInput || !previousId) { previousInput = serialized; previousId = crypto.randomUUID() }
    return previousId
  }
}
