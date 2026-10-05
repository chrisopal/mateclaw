export function presalesError(error: unknown): {
  conflict: boolean
  accessDenied: boolean
  message: string
} {
  const value = errorRecord(error)
  const response = errorRecord(value?.response)
  const data = errorRecord(response?.data)
  const detail = errorRecord(data?.data)
  const code = errorText(detail?.code) || errorText(data?.code)
  return {
    accessDenied: response?.status === 403,
    conflict: code
      ? ['VERSION_CONFLICT', 'OPERATION_CONFLICT', 'OPERATION_REPLAY_UNVERIFIABLE'].includes(code)
      : response?.status === 409,
    message:
      errorText(data?.msg) ||
      errorText(data?.message) ||
      errorText(value?.message) ||
      'Request failed',
  }
}

function errorRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined
}

function errorText(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

export function coverageLabel(inScope: number, responded: number): string {
  return inScope === 0 ? '—' : `${responded}/${inScope}`
}
/** A response from a previous workspace or route must never replace current data. */
export function isCurrentRequest(
  capturedWorkspace: string,
  currentWorkspace: string | null,
  capturedId: string,
  currentId: string,
): boolean {
  return capturedWorkspace === currentWorkspace && capturedId === currentId
}
/** Keep the same receipt key when a timeout leaves a mutation outcome uncertain. */
export function operationReceipt() {
  let previousInput = '',
    previousId = ''
  return (input: unknown): string => {
    const serialized = JSON.stringify(input)
    if (serialized !== previousInput || !previousId) {
      previousInput = serialized
      previousId = crypto.randomUUID()
    }
    return previousId
  }
}
