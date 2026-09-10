import { describe, expect, it } from 'vitest'
import type { AxiomDescriptor } from '../../api/types'
import { projectOntology } from '../ontologyProjection'
const axiom = (rendering: string, axiomId = rendering): AxiomDescriptor => ({ axiomId, rendering, axiomType: 'display', signatureIris: ['urn:A', 'urn:B'], annotations: [], logical: true })
describe('ontology projection', () => {
  it('preserves punning and escaped labels with nested annotations', () => {
    const result = projectOntology([
      axiom(String.raw`AnnotationAssertion(Annotation(Annotation(<urn:meta> "nested") <urn:source> "a (b)") rdfs:label <urn:A> "设备 \"A\""@zh)`, 'label'),
      axiom('Declaration(Class(<urn:A>))', 'class'), axiom('Declaration(NamedIndividual(<urn:A>))', 'individual'),
    ])
    expect(result.nodes.map(n => n.id)).toEqual(['class:urn:A', 'individual:urn:A'])
    expect(result.nodes.map(n => n.label)).toEqual(['设备 "A"', '设备 "A"'])
    expect(result.nodes[0]?.axiomIds).toEqual(['class', 'label'])
    expect(result.unprojectedAxiomIds).toEqual([])
  })
  it('renders explicit domain and range through typed property nodes', () => {
    const result = projectOntology([
      axiom('SubClassOf(<urn:A> <urn:B>)'), axiom('ObjectPropertyDomain(<urn:p> <urn:A>)'),
      axiom('ObjectPropertyRange(<urn:p> <urn:B>)'), axiom('DataPropertyDomain(<urn:q> <urn:A>)'), axiom('DataPropertyRange(<urn:q> xsd:string)'),
    ])
    expect(result.edges.map(e => [e.source, e.target, e.kind])).toEqual([
      ['class:urn:A', 'class:urn:B', 'subClassOf'], ['class:urn:A', 'objectProperty:urn:p', 'domain'],
      ['objectProperty:urn:p', 'class:urn:B', 'range'], ['class:urn:A', 'dataProperty:urn:q', 'domain'],
      ['dataProperty:urn:q', 'datatype:http://www.w3.org/2001/XMLSchema#string', 'range'],
    ])
    expect(result.unprojectedAxiomIds).toEqual([])
  })
  it('does not fabricate from signatures, restrictions, inverse expressions or unsupported axioms', () => {
    const inputs = ['SubClassOf(<urn:A> ObjectSomeValuesFrom(<urn:p> ObjectIntersectionOf(<urn:B> <urn:C>)))', 'ObjectPropertyDomain(ObjectInverseOf(<urn:p>) <urn:A>)', 'EquivalentClasses(<urn:A> <urn:B>)', 'SubClassOf(<urn:A> <urn:B>', 'SubClassOf(<urn:A> <urn:B>) garbage', 'AnnotationAssertion(rdfs:label <urn:unknown> "Standalone")', 'ObjectPropertyRange(<urn:p> ObjectIntersectionOf(<urn:A> <urn:B>))'].map((s, i) => axiom(s, String(i)))
    const result = projectOntology(inputs)
    expect(result.nodes).toEqual([]); expect(result.edges).toEqual([])
    expect(result.unprojectedAxiomIds.sort()).toEqual(inputs.map(a => a.axiomId).sort())
  })
  it('ignores annotation references when projecting direct named endpoints', () => {
    const result = projectOntology([axiom(String.raw`SubClassOf(Annotation(<urn:note> "with \"quote\" and \\ slash") <https://example.org/A> <https://example.org/B>)`)])
    expect(result.edges).toHaveLength(1)
    expect(result.nodes.map(n => n.iri)).toEqual(['https://example.org/A', 'https://example.org/B'])
  })
  it('distinguishes existential and universal class constraints, resolving later property labels', () => {
    const result = projectOntology([
      axiom('SubClassOf(<urn:A> ObjectSomeValuesFrom(<urn:p> <urn:B>))', 'some'),
      axiom('SubClassOf(<urn:A> ObjectAllValuesFrom(<urn:q> <urn:B>))', 'only'),
      axiom('AnnotationAssertion(rdfs:label <urn:p> "包含")', 'label'),
    ])
    expect(result.edges.map(e => [e.kind, e.label, e.source, e.target])).toEqual([
      ['someValuesFrom', '包含 · 至少一个', 'class:urn:A', 'class:urn:B'],
      ['allValuesFrom', 'q · 仅限', 'class:urn:A', 'class:urn:B'],
    ])
    expect(result.nodes.map(n => [n.kind, n.label])).toEqual([['class', 'A'], ['class', 'B']])
    expect(result.unprojectedAxiomIds).toEqual([])
  })
  it('retains annotated restrictions for full axiom inspection and rejects extra structure', () => {
    const result = projectOntology([
      axiom('SubClassOf(Annotation(<urn:source> "manual") <urn:A> ObjectSomeValuesFrom(<urn:p> <urn:B>))', 'annotated'),
      axiom('SubClassOf(<urn:A> ObjectAllValuesFrom(ObjectInverseOf(<urn:p>) <urn:B>))', 'inverse'),
      axiom('SubClassOf(<urn:A> ObjectAllValuesFrom(<urn:p> <urn:B> <urn:C>))', 'extra'),
    ])
    expect(result.edges).toHaveLength(1)
    expect(result.unprojectedAxiomIds).toEqual(['annotated', 'inverse', 'extra'])
  })

})
