import { describe, expect, it } from 'vitest'
import type { AxiomDescriptor } from '../../api/types'
import type { DisplayExpression, DisplayProjection } from '../standardProjection'
import {
  editableRestrictions,
  restrictionEdits,
  type EditableRestriction,
  type RestrictionOperator,
} from '../restrictionEditing'

const axiom = (id: string, annotations: AxiomDescriptor['annotations'] = [], axiomType = 'SubClassOf'): AxiomDescriptor => ({
  axiomId: id, axiomType, rendering: 'opaque server rendering', signatureIris: [], annotations, logical: true,
})

function projection(operator: RestrictionOperator, options: { imported?: boolean; status?: 'FULL' | 'PARTIAL' | 'NOT_RENDERED'; annotated?: boolean; nested?: boolean } = {}): DisplayProjection {
  const rootId = 'draft:qualified-subclass'
  const nestedId = `${rootId}:axiom/superClass`
  const nested: DisplayExpression = {
    id: nestedId, axiomId: rootId, path: 'axiom/superClass', operator,
    operands: [
      { role: 'property', position: 0, targetId: 'objectProperty:urn:p', value: null },
      ...(operator.endsWith('Cardinality') ? [{ role: 'cardinality', position: 1, targetId: null, value: '0' }] : []),
      { role: 'filler', position: operator.endsWith('Cardinality') ? 2 : 1, targetId: options.nested ? `${nestedId}:filler` : 'class:urn:B', value: null },
    ],
  }
  const expressions: DisplayExpression[] = [
    { id: rootId, axiomId: rootId, path: 'axiom', operator: 'SubClassOf', operands: [
      { role: 'subClass', position: 0, targetId: 'class:urn:A', value: null },
      { role: 'superClass', position: 1, targetId: nestedId, value: null },
    ] },
    nested,
  ]
  if (options.nested) expressions.push({
    id: `${nestedId}:filler`, axiomId: rootId, path: 'axiom/superClass/filler', operator: 'ObjectIntersectionOf', operands: [],
  })
  return {
    schemaVersion: 'ontology-display-v1', documentDigest: 'd', importLockDigest: 'i',
    nodes: [
      { id: 'class:urn:A', iri: 'urn:A', kind: 'class', labels: [], axiomIds: [], features: [], imported: false },
      { id: 'class:urn:B', iri: 'urn:B', kind: 'class', labels: [], axiomIds: [], features: [], imported: false },
      { id: 'objectProperty:urn:p', iri: 'urn:p', kind: 'objectProperty', labels: [], axiomIds: [], features: [], imported: false },
    ], edges: [], expressions, axiomRefs: [{
      id: rootId, artifactId: 'draft', axiomId: 'local-subclass', imported: options.imported ?? false,
      rendering: 'opaque server rendering', axiomType: 'SubClassOf', status: options.status ?? 'FULL', reason: '',
    }], coverage: { total: 1, returned: 1, truncated: false, dependencyScope: 'ROOT', lockedImportCount: 0 },
  }
}

describe('restrictionEditing', () => {
  it.each([
    'ObjectSomeValuesFrom', 'ObjectAllValuesFrom', 'ObjectMinCardinality',
    'ObjectMaxCardinality', 'ObjectExactCardinality',
  ] as RestrictionOperator[])('recognizes complete %s restrictions from projection expressions', operator => {
    const result = editableRestrictions(projection(operator), [axiom('local-subclass')], 'class:urn:A')
    expect(result).toHaveLength(1)
    expect(result[0]!.input).toEqual({
      subject: 'class:urn:A', operator, property: 'objectProperty:urn:p', filler: 'class:urn:B',
      cardinality: operator.endsWith('Cardinality') ? '0' : '',
    })
  })

  it('requires a full local source ref, exact descriptor id, and no nested restriction expressions', () => {
    expect(editableRestrictions(projection('ObjectSomeValuesFrom'), [axiom('wrong-id')], 'class:urn:A')).toEqual([])
    expect(editableRestrictions(projection('ObjectSomeValuesFrom', { imported: true }), [axiom('local-subclass')], 'class:urn:A')).toEqual([])
    expect(editableRestrictions(projection('ObjectSomeValuesFrom', { status: 'PARTIAL' }), [axiom('local-subclass')], 'class:urn:A')).toEqual([])
    expect(editableRestrictions(projection('ObjectSomeValuesFrom', { annotated: true }), [axiom('local-subclass', [{ propertyIri: 'urn:note', valueRendering: '"x"', nestedAnnotations: [] }])], 'class:urn:A')).toEqual([])
    expect(editableRestrictions(projection('ObjectSomeValuesFrom', { nested: true }), [axiom('local-subclass')], 'class:urn:A')).toEqual([])
  })

  it('keeps punning typed and returns deterministic remove/add edits', () => {
    const result = editableRestrictions(projection('ObjectSomeValuesFrom'), [axiom('local-subclass')], 'class:urn:A')
    const original: EditableRestriction = result[0]!
    expect(restrictionEdits(original.input, original)).toEqual([])
    expect(restrictionEdits({ ...original.input, operator: 'ObjectAllValuesFrom' }, original)).toEqual([
      { kind: 'REMOVE', axiomId: 'local-subclass' },
      { kind: 'ADD', functionalSyntax: 'SubClassOf(<urn:A> ObjectAllValuesFrom(<urn:p> <urn:B>))' },
    ])
    expect(editableRestrictions(projection('ObjectSomeValuesFrom'), [axiom('local-subclass')], 'individual:urn:A')).toEqual([])
  })

  it('validates typed IRI inputs and bounded nonnegative cardinalities', () => {
    const input = { subject: 'class:urn:A', operator: 'ObjectMinCardinality' as const, property: 'objectProperty:urn:p', filler: 'class:urn:B', cardinality: '0' }
    expect(restrictionEdits(input)).toEqual([{ kind: 'ADD', functionalSyntax: 'SubClassOf(<urn:A> ObjectMinCardinality(0 <urn:p> <urn:B>))' }])
    expect(() => restrictionEdits({ ...input, subject: 'class:not-an-iri' })).toThrow('INVALID_SUBJECT')
    expect(() => restrictionEdits({ ...input, property: 'class:urn:p' })).toThrow('INVALID_PROPERTY')
    expect(() => restrictionEdits({ ...input, cardinality: '-1' })).toThrow('INVALID_CARDINALITY')
    expect(() => restrictionEdits({ ...input, cardinality: '2147483648' })).toThrow('INVALID_CARDINALITY')
    expect(() => restrictionEdits({ ...input, cardinality: '01' })).toThrow('INVALID_CARDINALITY')
    expect(() => restrictionEdits({ ...input, operator: 'ObjectSomeValuesFrom', cardinality: '1' })).toThrow('INVALID_CARDINALITY')
  })

  it('uses expression metadata even when rendering is opaque and refuses unsafe originals', () => {
    const original: EditableRestriction = {
      axiom: axiom('local-subclass'),
      input: { subject: 'class:urn:A', operator: 'ObjectSomeValuesFrom', property: 'objectProperty:urn:p', filler: 'class:urn:B', cardinality: '' },
    }
    expect(restrictionEdits({ ...original.input, filler: 'class:urn:C' }, original)[0]).toEqual({ kind: 'REMOVE', axiomId: 'local-subclass' })
    expect(() => restrictionEdits(original.input, { ...original, axiom: axiom('local-subclass', [], 'EquivalentClasses') })).toThrow('INVALID_ORIGINAL_RESTRICTION')
    expect(() => restrictionEdits(original.input, { ...original, axiom: { ...original.axiom, logical: false } })).toThrow('INVALID_ORIGINAL_RESTRICTION')
  })
})
