import type { ValidationReport } from '../api/types'

export const requiredChecks = ['STRUCTURE', 'LOGIC', 'POLICY', 'SOURCES'] as const

/** Legacy structural reports never authorize publication. The server rechecks independently. */
export function isPublicationReport(report: ValidationReport | null, draftVersion: number): boolean {
  return !!report?.reportId && !!report.inputDigest && report.valid === true && report.stale === false
    && report.draftVersion === draftVersion
    && requiredChecks.every(kind => report.checks?.filter(check => check.kind === kind).length === 1
      && report.checks.find(check => check.kind === kind)?.status === 'PASS')
}
