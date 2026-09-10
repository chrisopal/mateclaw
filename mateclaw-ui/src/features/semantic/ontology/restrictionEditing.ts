import type { AxiomDescriptor, AxiomEdit } from '../api/types'
import type { DisplayExpression, DisplayExpressionOperand, DisplayProjection } from './standardProjection'
import { validOntologyIri } from './ontologyForm'

export const restrictionOperators = [
  'ObjectSomeValuesFrom',
  'ObjectAllValuesFrom',
  'ObjectMinCardinality',
  'ObjectMaxCardinality',
  'ObjectExactCardinality',
] as const

export type RestrictionOperator = typeof restrictionOperators[number]

export interface RestrictionInput {
  subject: string
  operator: RestrictionOperator
  property: string
  filler: string
  cardinality: string
}

export interface EditableRestriction {
  axiom: AxiomDescriptor
  input: RestrictionInput
}

const operatorSet = new Set<string>(restrictionOperators)

function typedNodeId(value: string): { kind: string; iri: string } | null {
  const separator = value.indexOf(':')
  if (separator <= 0 || separator === value.length - 1) return null
  return { kind: value.slice(0, separator), iri: value.slice(separator + 1) }
}

function nodeFor(
  projection: DisplayProjection,
  id: string | null | undefined,
  kind: 'class' | 'objectProperty',
): DisplayProjection['nodes'][number] | null {
  if (!id) return null
  const parsed = typedNodeId(id)
  if (!parsed || parsed.kind !== kind || !validOntologyIri(parsed.iri)) return null
  const node = projection.nodes.find(candidate => candidate.id === id)
  return node && node.id === `${kind}:${node.iri}` && node.kind === kind && !node.imported ? node : null
}

function operand(expression: DisplayExpression, role: string, position: number): DisplayExpressionOperand | null {
  const found = expression.operands.find(candidate => candidate.role === role && candidate.position === position)
  return found ?? null
}

function sourceRef(
  projection: DisplayProjection,
  axioms: AxiomDescriptor[],
  expression: DisplayExpression,
): { ref: DisplayProjection['axiomRefs'][number]; axiom: AxiomDescriptor } | null {
  const ref = projection.axiomRefs.find(candidate =>
    candidate.id === expression.axiomId && !candidate.imported && candidate.status === 'FULL')
  if (!ref) return null
  const axiom = axioms.find(candidate => candidate.axiomId === ref.axiomId
    && candidate.axiomType === 'SubClassOf' && candidate.annotations.length === 0)
  return axiom ? { ref, axiom } : null
}

function extractRestriction(
  projection: DisplayProjection,
  source: { ref: DisplayProjection['axiomRefs'][number]; axiom: AxiomDescriptor },
  root: DisplayExpression,
  subject: string,
  byId: Map<string, DisplayExpression>,
): RestrictionInput | null {
  if (root.path !== 'axiom' || root.operator !== 'SubClassOf' || root.operands.length !== 2) return null
  const subClass = operand(root, 'subClass', 0)
  const superClass = operand(root, 'superClass', 1)
  if (!subClass || subClass.targetId !== subject || !nodeFor(projection, subject, 'class')) return null
  if (!superClass || !superClass.targetId) return null
  const restriction = byId.get(superClass.targetId)
  if (!restriction || restriction.axiomId !== source.ref.id || restriction.path !== 'axiom/superClass'
    || !operatorSet.has(restriction.operator)) return null

  const operator = restriction.operator as RestrictionOperator
  const cardinalityOperator = operator.endsWith('Cardinality')
  const expectedOperands = cardinalityOperator ? 3 : 2
  if (restriction.operands.length !== expectedOperands) return null
  const property = operand(restriction, 'property', 0)
  const fillerPosition = cardinalityOperator ? 2 : 1
  const filler = operand(restriction, 'filler', fillerPosition)
  if (!property || !filler || !nodeFor(projection, property.targetId, 'objectProperty')
    || !nodeFor(projection, filler.targetId, 'class')) return null
  if (restriction.operands.some(candidate => candidate.targetId && byId.has(candidate.targetId))) return null

  let cardinality = ''
  if (cardinalityOperator) {
    const value = operand(restriction, 'cardinality', 1)?.value
    if (!value || !/^(?:0|[1-9]\d*)$/u.test(value) || Number(value) > 2147483647) return null
    cardinality = value
  }
  return { subject, operator, property: property.targetId!, filler: filler.targetId!, cardinality }
}

/** Recognize only complete, unannotated root restrictions represented by the server projection. */
export function editableRestrictions(
  projection: DisplayProjection | null | undefined,
  axioms: AxiomDescriptor[],
  subject: string,
): EditableRestriction[] {
  if (!projection || !nodeFor(projection, subject, 'class')) return []
  const byId = new Map((projection.expressions ?? []).map(expression => [expression.id, expression]))
  return (projection.expressions ?? [])
    .filter(expression => expression.path === 'axiom' && expression.operator === 'SubClassOf')
    .sort((left, right) => left.id.localeCompare(right.id))
    .flatMap(root => {
      const source = sourceRef(projection, axioms, root)
      if (!source) return []
      const input = extractRestriction(projection, source, root, subject, byId)
      return input ? [{ axiom: source.axiom, input }] : []
    })
}

function validateNodeInput(value: string, kind: 'class' | 'objectProperty', field: string): string {
  const parsed = typedNodeId(value)
  if (!parsed || parsed.kind !== kind || !validOntologyIri(parsed.iri)) throw new Error(`INVALID_${field.toUpperCase()}`)
  return parsed.iri
}

function validateInput(input: RestrictionInput): RestrictionInput & { subjectIri: string; propertyIri: string; fillerIri: string } {
  if (!input || !operatorSet.has(input.operator)) throw new Error('INVALID_RESTRICTION_OPERATOR')
  if (typeof input.cardinality !== 'string') throw new Error('INVALID_CARDINALITY')
  const subjectIri = validateNodeInput(input.subject, 'class', 'subject')
  const propertyIri = validateNodeInput(input.property, 'objectProperty', 'property')
  const fillerIri = validateNodeInput(input.filler, 'class', 'filler')
  const cardinalityOperator = input.operator.endsWith('Cardinality')
  if (cardinalityOperator) {
    if (!/^(?:0|[1-9]\d*)$/u.test(input.cardinality) || Number(input.cardinality) > 2147483647) {
      throw new Error('INVALID_CARDINALITY')
    }
  } else if (input.cardinality !== '') {
    throw new Error('INVALID_CARDINALITY')
  }
  return { ...input, subjectIri, propertyIri, fillerIri }
}

function validateOriginal(original: EditableRestriction): void {
  if (!original || !original.axiom || original.axiom.axiomType !== 'SubClassOf'
    || original.axiom.annotations.length !== 0 || !original.axiom.axiomId.trim() || !original.axiom.logical) {
    throw new Error('INVALID_ORIGINAL_RESTRICTION')
  }
  validateInput(original.input)
}

function sameInput(left: RestrictionInput, right: RestrictionInput): boolean {
  return left.subject === right.subject && left.operator === right.operator
    && left.property === right.property && left.filler === right.filler
    && left.cardinality === right.cardinality
}

function restrictionSyntax(input: RestrictionInput & { subjectIri: string; propertyIri: string; fillerIri: string }): string {
  const restriction = input.operator.endsWith('Cardinality')
    ? `${input.operator}(${input.cardinality} <${input.propertyIri}> <${input.fillerIri}>)`
    : `${input.operator}(<${input.propertyIri}> <${input.fillerIri}>)`
  return `SubClassOf(<${input.subjectIri}> ${restriction})`
}

export function restrictionEdits(input: RestrictionInput, original?: EditableRestriction): AxiomEdit[] {
  const validated = validateInput(input)
  if (original) {
    validateOriginal(original)
    if (sameInput(original.input, input)) return []
  }
  const add: AxiomEdit = { kind: 'ADD', functionalSyntax: restrictionSyntax(validated) }
  return original ? [{ kind: 'REMOVE', axiomId: original.axiom.axiomId }, add] : [add]
}
