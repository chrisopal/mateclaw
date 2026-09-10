import type { AxiomEdit } from '../api/types'
export interface TermInput { kind: 'Class' | 'ObjectProperty' | 'DataProperty'; iri: string; label: string; domain: string; range: string }
export function validOntologyIri(value: string) {
  return /^[A-Za-z][A-Za-z0-9+.-]*:[^\s<>"{}|^`\\]+$/u.test(value)
}
export function termEdits(input: TermInput): AxiomEdit[] {
  if (!validOntologyIri(input.iri) || !input.label.trim()) throw new Error('INVALID_TERM')
  if (input.kind !== 'Class' && (!validOntologyIri(input.domain) || !validOntologyIri(input.range))) throw new Error('INVALID_ENDPOINT')
  const literal = input.label.trim().replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t')
  const axioms = [`Declaration(${input.kind}(<${input.iri}>))`, `AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <${input.iri}> "${literal}")`]
  if (input.kind !== 'Class') axioms.push(`${input.kind}Domain(<${input.iri}> <${input.domain}>)`, `${input.kind}Range(<${input.iri}> <${input.range}>)`)
  return axioms.map(functionalSyntax => ({ kind: 'ADD', functionalSyntax }))
}
