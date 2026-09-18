import { expect, it } from 'vitest'
import { coverageLabel, isCurrentRequest, presalesError, operationReceipt } from '../shared/state'
it('does not invent 100 percent coverage for an empty baseline', () => {
  expect(coverageLabel(0, 0)).toBe('—')
  expect(coverageLabel(4, 2)).toBe('2/4')
})
it('rejects old workspace and old route results without numeric ID coercion', () => {
  expect(isCurrentRequest('90071992547409999', '90071992547409998', 'a', 'a')).toBe(false)
  expect(isCurrentRequest('90071992547409999', '90071992547409999', 'a', 'b')).toBe(false)
  expect(isCurrentRequest('90071992547409999', '90071992547409999', 'a', 'a')).toBe(true)
})
it('recognizes conflicts while preserving the actionable server error', () => {
  expect(presalesError({ response: { status: 409, data: { msg: 'Reload before saving' } } })).toEqual({ conflict: true, message: 'Reload before saving' })
})

it('retries identical mutations with the same receipt but isolates changed input', () => {
  const receipt = operationReceipt()
  const original = receipt({ ws: 'a', version: 2, name: 'First' })
  expect(receipt({ ws: 'a', version: 2, name: 'First' })).toBe(original)
  expect(receipt({ ws: 'b', version: 2, name: 'First' })).not.toBe(original)
})

it('does not lock project editing when the assigned employee is unavailable', () => {
  expect(presalesError({ response: { status: 409, data: { msg: 'EMPLOYEE_UNAVAILABLE', data: { code: 'EMPLOYEE_UNAVAILABLE' } } } }).conflict).toBe(false)
})
