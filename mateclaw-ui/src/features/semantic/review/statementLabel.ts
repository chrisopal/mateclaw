import type { Statement } from '../api/workbenchTypes'
import type { SemanticEntity } from '../api/types'

/** Presentation only; the canonical assertion remains in the advanced view. */
export function statementLabel(statement: Pick<Statement, 'subjectId' | 'assertion'>, entities: SemanticEntity[], language = 'zh-CN') {
  const zh = language.startsWith('zh')
  const short = (iri?: string | null) => iri?.split(/[#/:]/).filter(Boolean).at(-1) || '—'
  const name = (iri?: string | null) => entities.find(entity => entity.iri === iri)?.displayName || short(iri)
  const subject = entities.find(entity => entity.id === statement.subjectId)?.displayName || name(statement.assertion?.subjectIri)
  const a = statement.assertion
  if (!a) return zh ? '知识内容暂不可用' : 'Knowledge content unavailable'
  switch (a.kind) {
    case 'CLASS_ASSERTION': {
      const expression = a.classExpressionFunctionalSyntax?.trim()
      const named = expression?.match(/^<([^<>\s]+)>$/)?.[1]
      if (named) return `${subject} · ${zh ? '类型：' : 'Type: '}${short(named)}`
      return `${subject} · ${expression ? (zh ? '复合类型（查看详情）' : 'Complex type (see details)') : (zh ? '对象类型确认' : 'Object type assertion')}`
    }
    case 'POSITIVE_DATA_PROPERTY': return `${subject} · ${short(a.predicateIri)}：${a.literal?.lexicalValue ?? '—'}`
    case 'NEGATIVE_DATA_PROPERTY': return `${subject} · ${short(a.predicateIri)} ${zh ? '不是' : 'is not'} ${a.literal?.lexicalValue ?? '—'}`
    case 'POSITIVE_OBJECT_PROPERTY': return `${subject} · ${short(a.predicateIri)} → ${name(a.objectIri)}`
    case 'NEGATIVE_OBJECT_PROPERTY': return `${subject} · ${zh ? '不存在关系' : 'No relation'} ${short(a.predicateIri)} → ${name(a.objectIri)}`
    case 'SAME_INDIVIDUAL': return `${subject} ${zh ? '与' : 'and'} ${name(a.relatedIndividualIri)} · ${zh ? '同一对象' : 'Same object'}`
    case 'DIFFERENT_INDIVIDUAL': return `${subject} ${zh ? '与' : 'and'} ${name(a.relatedIndividualIri)} · ${zh ? '不同对象' : 'Different objects'}`
  }
}
