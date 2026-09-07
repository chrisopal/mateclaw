/** DOM selection offsets are UTF-16; the snapshot API counts Unicode code points. */
export function evidenceSelection(text: string, startUtf16: number, endUtf16: number) {
  if (startUtf16 < 0 || endUtf16 > text.length || startUtf16 >= endUtf16) throw new Error('Select an exact source passage')
  const startCodePoint = Array.from(text.slice(0, startUtf16)).length
  const endCodePoint = Array.from(text.slice(0, endUtf16)).length
  const exactQuote = text.slice(startUtf16, endUtf16)
  if (Array.from(text).slice(startCodePoint, endCodePoint).join('') !== exactQuote) throw new Error('Selection splits a Unicode character')
  return { startCodePoint, endCodePoint, exactQuote }
}
