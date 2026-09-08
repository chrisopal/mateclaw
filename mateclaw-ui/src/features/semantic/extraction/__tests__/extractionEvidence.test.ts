import { expect, it } from 'vitest'
import { evidenceParts } from '../extractionEvidence'
it('highlights code point offsets rather than UTF-16 indexes', () => {
  expect(evidenceParts('甲😀乙', { startCodePoint: 1, endCodePoint: 2, exactQuote: '😀' })).toEqual({ before: '甲', quote: '😀', after: '乙' })
})
it('rejects mismatched, inverted and out-of-range evidence without guessing', () => {
  for (const quote of [
    { startCodePoint: 1, endCodePoint: 2, exactQuote: '乙' },
    { startCodePoint: -1, endCodePoint: 2, exactQuote: '甲😀' },
    { startCodePoint: 2, endCodePoint: 1, exactQuote: '' },
    { startCodePoint: 0, endCodePoint: 99, exactQuote: '甲😀乙' },
  ]) expect(evidenceParts('甲😀乙', quote)).toBeNull()
})
