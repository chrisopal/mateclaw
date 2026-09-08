import type { ExtractionQuote } from '../api/extractionApi'
export function evidenceParts(text: string, quote: ExtractionQuote) {
  const points = Array.from(text)
  const { startCodePoint: start, endCodePoint: end } = quote
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > points.length) return null
  const selected = points.slice(start, end).join('')
  if (selected !== quote.exactQuote) return null
  return { before: points.slice(0, start).join(''), quote: selected, after: points.slice(end).join('') }
}
