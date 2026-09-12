import { expect, it } from 'vitest'
import { statementLabel } from '../statementLabel'
import type { Statement } from '../../api/workbenchTypes'
it('keeps positive and negative relationships distinct in business wording', () => {
  const statement = { subjectId: '1', assertion: { kind: 'NEGATIVE_OBJECT_PROPERTY', subjectIri: 'urn:Pump', predicateIri: 'urn:installedAt', objectIri: 'urn:Site' } } as Statement
  expect(statementLabel(statement, [])).toContain('不存在关系')
  statement.assertion.kind = 'POSITIVE_OBJECT_PROPERTY'
  expect(statementLabel(statement, [])).toBe('Pump · installedAt → Site')
})
it('does not render unknown class expressions as a guessed business class', () => {
  const statement = { subjectId: '1', assertion: { kind: 'CLASS_ASSERTION', subjectIri: 'urn:Pump' } } as Statement
  expect(statementLabel(statement, [])).toBe('Pump · 对象类型确认')
})

it('distinguishes named types without guessing a complex expression', () => {
 const statement = { subjectId: '1', assertion: { kind: 'CLASS_ASSERTION', subjectIri: 'urn:Pump', classExpressionFunctionalSyntax: '<urn:Equipment>' } } as Statement
 expect(statementLabel(statement, [])).toBe('Pump · 类型：Equipment')
 statement.assertion.classExpressionFunctionalSyntax = 'ObjectIntersectionOf(<urn:Equipment> <urn:Sensor>)'
 expect(statementLabel(statement, [])).toContain('复合类型')
})

it('uses published business labels while preserving assertion identifiers', () => {
 const statement = { subjectId: '1', assertion: { kind: 'CLASS_ASSERTION', subjectIri: 'urn:Pump', classExpressionFunctionalSyntax: '<urn:hash>' } } as Statement
 expect(statementLabel(statement, [], 'zh-CN', new Map([['urn:hash', '泵']]))).toBe('Pump · 类型：泵')
 expect(statement.assertion.classExpressionFunctionalSyntax).toBe('<urn:hash>')
})
