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
  expect(
    presalesError({ response: { status: 409, data: { msg: 'Reload before saving' } } }),
  ).toEqual({ conflict: true, accessDenied: false, message: 'Reload before saving' })
})

it.each(['nested', 'top-level'])(
  'recognizes an unverifiable legacy receipt from the %s error code',
  (shape) => {
    const code = 'OPERATION_REPLAY_UNVERIFIABLE'
    const msg = 'Stored operation cannot be safely verified; review before retrying'
    expect(
      presalesError({
        response: {
          status: 409,
          data: shape === 'nested' ? { msg, data: { code } } : { msg, code },
        },
      }),
    ).toEqual({ conflict: true, accessDenied: false, message: msg })
  },
)

it('retries identical mutations with the same receipt but isolates changed input', () => {
  const receipt = operationReceipt()
  const original = receipt({ ws: 'a', version: 2, name: 'First' })
  expect(receipt({ ws: 'a', version: 2, name: 'First' })).toBe(original)
  expect(receipt({ ws: 'b', version: 2, name: 'First' })).not.toBe(original)
})

it('does not lock project editing when the assigned employee is unavailable', () => {
  expect(
    presalesError({
      response: {
        status: 409,
        data: { msg: 'EMPLOYEE_UNAVAILABLE', data: { code: 'EMPLOYEE_UNAVAILABLE' } },
      },
    }).conflict,
  ).toBe(false)
})

it('distinguishes forbidden reads from revision conflicts', () => {
  expect(
    presalesError({ response: { status: 403, data: { msg: 'Source access denied' } } }),
  ).toEqual({ conflict: false, accessDenied: true, message: 'Source access denied' })
})

it.each([7, true, {}, ['VERSION_CONFLICT']])(
  'ignores malformed code %j without losing a 409 or valid legacy code',
  (code) => {
    expect(presalesError({ response: { status: 409, data: { data: { code } } } })).toEqual({
      conflict: true,
      accessDenied: false,
      message: 'Request failed',
    })
    expect(
      presalesError({
        response: {
          status: 400,
          data: {
            code: 'VERSION_CONFLICT',
            data: { code },
          },
        },
      }).conflict,
    ).toBe(true)
  },
)

it.each([5, true, {}, ['private', 'text']])(
  'does not return malformed message %j as text',
  (msg) => {
    expect(
      presalesError({
        message: 'Network fallback',
        response: { status: 403, data: { msg, message: 'Safe message' } },
      }),
    ).toEqual({ conflict: false, accessDenied: true, message: 'Safe message' })
    expect(presalesError({ message: msg, response: { data: { msg, message: msg } } }).message).toBe(
      'Request failed',
    )
  },
)

it.each([undefined, null, 7, 'plain text', [], { response: null }, { response: { data: [] } }])(
  'safely projects a non-envelope error %j',
  (error) => {
    expect(presalesError(error)).toEqual({
      conflict: false,
      accessDenied: false,
      message: 'Request failed',
    })
  },
)

it('keeps legitimate error precedence, unknown codes and exact text', () => {
  expect(presalesError(new Error('Network failed')).message).toBe('Network failed')
  expect(
    presalesError({
      message: 'network',
      response: {
        status: 409,
        data: {
          msg: ' Exact server text ',
          message: 'legacy',
          code: 'VERSION_CONFLICT',
          data: { code: 'FUTURE_BUSINESS_CODE' },
        },
      },
    }),
  ).toEqual({ conflict: false, accessDenied: false, message: ' Exact server text ' })
  expect(
    presalesError({
      message: 'network',
      response: {
        status: 409,
        data: {
          msg: '',
          message: 'legacy',
          code: '',
          data: { code: '' },
        },
      },
    }),
  ).toEqual({ conflict: true, accessDenied: false, message: 'legacy' })
})
