import { describe, expect, it } from 'vitest'
import type { AxiomDescriptor } from '../../api/types'
import { editableLabel, replaceLabel } from '../labelEditing'
function axiom(rendering: string): AxiomDescriptor {
  return { axiomId: 'label-id', axiomType: 'AnnotationAssertion', rendering, signatureIris: [], annotations: [], logical: false }
}
describe('simple label edits', () => {
  it('changes only the selected label with escaped literal and language', () => {
    const label = editableLabel(axiom('AnnotationAssertion(rdfs:label <urn:qa:Equipment> "Equipment"@en)'))!
    expect(label).toEqual({ axiomId: 'label-id', subject: 'urn:qa:Equipment', value: 'Equipment', language: 'en' })
    expect(replaceLabel(label, '测针 "A" \\ B', 'zh-CN')).toEqual([
      { kind: 'REMOVE', axiomId: 'label-id' },
      { kind: 'ADD', functionalSyntax: 'AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:qa:Equipment> "测针 \\"A\\" \\\\ B"@zh-CN)' },
    ])
  })
  it('does not flatten annotated labels or other axioms', () => {
    expect(editableLabel(axiom('SubClassOf(<urn:a> ObjectSomeValuesFrom(<urn:p> <urn:b>))'))).toBeNull()
    expect(editableLabel(axiom('AnnotationAssertion(Annotation(<urn:source> "x") rdfs:label <urn:a> "A")'))).toBeNull()
    expect(editableLabel(axiom('AnnotationAssertion(<urn:comment> <urn:a> "A")'))).toBeNull()
  })
  it('rejects invalid language and control characters before sending commands', () => {
    const label = editableLabel(axiom('AnnotationAssertion(rdfs:label <urn:a> "A")'))!
    expect(() => replaceLabel(label, 'A', 'en) Declaration(Class(<urn:bad>))')).toThrow()
    expect(() => replaceLabel(label, 'A\nB', '')).toThrow()
    expect(() => replaceLabel(label, ' ', '')).toThrow()
  })
})
