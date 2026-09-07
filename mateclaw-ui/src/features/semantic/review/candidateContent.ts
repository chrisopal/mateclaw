import type { Proposal } from '../api/workbenchTypes'
export function candidateContent(input: {
  subjectId: string; predicate: { kind: string; key: string; valueType: string; fixedUnit: string | null }
  value: string; targetEntityId: string; validityKind: string; validFrom: string; validTo: string
}): Proposal {
  const { predicate: p } = input
  return {
    operationId: crypto.randomUUID(), subjectId: input.subjectId, predicateKind: p.kind, predicateKey: p.key,
    valueType: p.kind === 'RELATION' ? 'ENTITY' : p.valueType,
    value: p.kind === 'PROPERTY' ? (p.valueType === 'INSTANT' ? new Date(input.value).toISOString() : input.value) : null,
    unit: p.fixedUnit, targetEntityId: p.kind === 'RELATION' ? input.targetEntityId : null,
    validityKind: input.validityKind,
    validFrom: input.validityKind === 'INTERVAL' && input.validFrom ? new Date(input.validFrom).toISOString() : null,
    validTo: input.validityKind === 'INTERVAL' && input.validTo ? new Date(input.validTo).toISOString() : null,
    evidenceIds: [],
  }
}
