import { expect, it } from 'vitest'
import { importDocumentFile } from '../importDocumentFile'
it('preserves standard document bytes and delegates semantic validation', () => {
  const text = 'Ontology(<urn:test> Import(<urn:locked>))\n'
  const result = importDocumentFile('example.ofn', text)
  expect(result.packageData.document.documentText).toBe(text)
  expect(result.packageData.document.syntax).toBe('FUNCTIONAL')
  expect(result.packageData.document.imports).toEqual([])
  expect(importDocumentFile('example.rdf', '<rdf:RDF/>').packageData.document.syntax).toBe('RDF_XML')
})
it('preserves original JSON for strict duplicate-key checks on server', () => {
  const raw = '{"packageFormatVersion":2,"name":"A","name":"B","document":{}}'
  expect(importDocumentFile('example.json', raw).rawPackage).toBe(raw)
})
it('rejects unsupported and empty files without guessing a syntax', () => {
  expect(() => importDocumentFile('example.ttl', 'text')).toThrow()
  expect(() => importDocumentFile('example.rdf', ' ')).toThrow()
  expect(() => importDocumentFile('example.json', '{}')).toThrow()
})
