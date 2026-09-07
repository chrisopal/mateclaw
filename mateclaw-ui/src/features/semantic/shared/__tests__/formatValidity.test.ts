import { describe, expect, it } from 'vitest'
import { formatValidity } from '../formatValidity'
const t = (key: string, values?: Record<string, string>) => `${key}${values ? ':' + values.time : ''}`
describe('business validity display', () => {
  it('keeps unknown time distinct from an unbounded interval', () => {
    expect(formatValidity({ validityKind: 'UNKNOWN', validFrom: null, validTo: null }, t)).toBe('semantic.w.unknown')
    expect(formatValidity({ validityKind: 'INTERVAL', validFrom: null, validTo: null }, t)).toBe('semantic.w.allTime')
  })
  it('labels each open boundary without an empty separator', () => {
    expect(formatValidity({ validityKind: 'INTERVAL', validFrom: '2026-01-01', validTo: null }, t)).toBe('semantic.w.fromTime:2026-01-01')
    expect(formatValidity({ validityKind: 'INTERVAL', validFrom: null, validTo: '2026-12-31' }, t)).toBe('semantic.w.untilTime:2026-12-31')
    expect(formatValidity({ validityKind: 'INTERVAL', validFrom: '2026-01-01', validTo: '2026-12-31' }, t)).toBe('2026-01-01 — 2026-12-31')
  })
})
