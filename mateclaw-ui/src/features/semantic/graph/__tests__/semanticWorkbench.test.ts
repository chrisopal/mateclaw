import { expect, it } from 'vitest'
import { evidenceSelection } from '../../review/evidenceSelection'
it('converts emoji and multi-paragraph selections to full-snapshot code point offsets', () => {
  const text = '😀设备\n额定380V\n第二段⚡400V'
  const start = text.indexOf('额定'), end = text.indexOf('400V') + 4
  const range = evidenceSelection(text, start, end)
  expect(range.startCodePoint).toBe(4)
  expect(Array.from(text).slice(range.startCodePoint, range.endCodePoint).join('')).toBe('额定380V\n第二段⚡400V')
})
it('rejects an empty range or half of a surrogate pair', () => {
  expect(() => evidenceSelection('😀a', 0, 1)).toThrow()
  expect(() => evidenceSelection('abc', 1, 1)).toThrow()
})
