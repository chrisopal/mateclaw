import type { Violation } from './types'
export interface SemanticError {
  status: number
  code: string
  message: string
  fieldErrors: Violation[]
  traceId?: string
}
export function semanticError(error: unknown): SemanticError {
  const e = error as Partial<SemanticError> & {
    response?: { status?: number; data?: { msg?: string; data?: Partial<SemanticError> } }
  }
  const details = e?.response?.data?.data ?? e ?? {}
  return {
    status: e?.response?.status ?? e?.status ?? 0,
    code: details.code ?? 'REQUEST_FAILED',
    message: e?.response?.data?.msg ?? e?.message ?? '',
    fieldErrors: details.fieldErrors ?? [],
    traceId: details.traceId,
  }
}
