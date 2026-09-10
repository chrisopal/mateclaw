import { describe, expect, it } from 'vitest'
import { termEdits } from '../ontologyForm'
describe('business term form', () => {
  it('atomically adds a relation with label and explicit domain/range', () => {
    const result = termEdits({kind:'ObjectProperty',iri:'urn:quality:uses',label:'使用测针',domain:'urn:quality:CMM',range:'urn:quality:Probe'})
    expect(result).toHaveLength(4)
    expect(result[2]?.functionalSyntax).toBe('ObjectPropertyDomain(<urn:quality:uses> <urn:quality:CMM>)')
    expect(result[3]?.functionalSyntax).toBe('ObjectPropertyRange(<urn:quality:uses> <urn:quality:Probe>)')
    expect(result.every(e=>e.kind==='ADD')).toBe(true)
  })
  it('escapes labels and rejects injected IRI or invalid endpoints', () => {
    const input = {kind:'Class' as const,iri:'urn:quality:CMM',label:'A "quoted" \\ label',domain:'',range:''}
    expect(termEdits(input)[1]?.functionalSyntax).toContain('A \\"quoted\\" \\\\ label')
    expect(()=>termEdits({...input,iri:'urn:x>) Declaration(Class(<urn:y'})).toThrow()
    expect(()=>termEdits({...input,kind:'DataProperty',domain:'',range:'xsd:string'})).toThrow()
  })
})
