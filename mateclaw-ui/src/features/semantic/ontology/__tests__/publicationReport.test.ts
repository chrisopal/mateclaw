import { expect, it } from 'vitest'
import { isPublicationReport, requiredChecks } from '../validationReport'
import type { ValidationReport } from '../../api/types'
const report = (): ValidationReport => ({ draftVersion: 2, valid: true, violations: [], reportId: '9223372036854775800', inputDigest: 'exact-input', stale: false, checks: requiredChecks.map(kind => ({ kind, status: 'PASS', violations: [] })) })
it('requires a current complete persisted report rather than legacy structural success', () => {
  expect(isPublicationReport(report(), 2)).toBe(true)
  expect(isPublicationReport({draftVersion:2, valid:true, violations:[]}, 2)).toBe(false)
  expect(isPublicationReport({...report(),stale:true}, 2)).toBe(false)
  expect(isPublicationReport(report(), 3)).toBe(false)
  for (const status of ['FAIL','NOT_RUN','ERROR','TIMEOUT']) {
    const value=report(); value.checks![1]!.status=status
    expect(isPublicationReport(value,2)).toBe(false)
  }
  expect(isPublicationReport({...report(),checks:report().checks!.slice(0,3)},2)).toBe(false)
})
