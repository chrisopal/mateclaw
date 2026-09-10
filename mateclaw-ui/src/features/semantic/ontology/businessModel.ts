import type { DisplayProjection } from './standardProjection'
import { displayModel } from './standardProjection'
/** References to CREATE_TERM client IDs use $clientId; IDs may point forward in the batch. */
export interface ModelChange {
  clientId?: string
  kind: 'CREATE_TERM' | 'REPLACE_DEFINITION' | 'REPLACE_RESTRICTION'
  termKind?: 'OBJECT' | 'RELATION' | 'ATTRIBUTE'
  targetId?: string
  name?: string
  domainId?: string
  rangeId?: string
  field?: 'NAME' | 'DESCRIPTION' | 'PARENT' | 'DOMAIN' | 'RANGE'
  value?: string
  language?: string
  operator?: 'SOME' | 'ALL' | 'MIN' | 'MAX' | 'EXACT'
  propertyId?: string
  fillerId?: string
  cardinality?: number
  originalAxiomId?: string
}
export interface ModelEdit { expectedDraftVersion: number; operationId: string; changes: ModelChange[] }
export interface ModelItemResult { clientId: string | null; targetId: string; axiomIds: string[] }
export interface ModelCommandResult<TDraft> { draft: TDraft; items: ModelItemResult[] }
export function businessRuleLabel(projection: DisplayProjection | null | undefined, axiomId: string, language = 'zh-CN'): string {
  const zh = language.startsWith('zh')
  const model = displayModel(projection, language)
  const labels = new Map(model.nodes.map(n => [n.id, n.label]))
  const ref = projection?.axiomRefs.find(a => a.axiomId === axiomId || a.id === axiomId)
  const expressions = projection?.expressions ?? []
  const root = expressions.find(e => e.axiomId === ref?.id && e.path === 'axiom')
  const describe = (id: string | null | undefined, depth = 0): string => {
    if (!id || depth > 12) return zh ? '组合条件' : 'Combined condition'
    if (labels.has(id)) return labels.get(id)!
    const e = expressions.find(x => x.id === id)
    if (!e) return zh ? '条件' : 'Condition'
    const part = (role: string) => describe(e.operands.find(o => o.role === role)?.targetId, depth + 1)
    const count = e.operands.find(o => o.role === 'cardinality')?.value ?? ''
    if (e.operator === 'SubClassOf') return `${part('subClass')} · ${part('superClass')}`
    const op: Record<string, string> = zh ? { ObjectSomeValuesFrom:'至少关联一个', ObjectAllValuesFrom:'关联对象均为', ObjectMinCardinality:`至少关联 ${count} 个`, ObjectMaxCardinality:`最多关联 ${count} 个`, ObjectExactCardinality:`恰好关联 ${count} 个` } : {ObjectSomeValuesFrom:'at least one',ObjectAllValuesFrom:'only',ObjectMinCardinality:`at least ${count}`,ObjectMaxCardinality:`at most ${count}`,ObjectExactCardinality:`exactly ${count}`}
    if (op[e.operator]) return `${part('property')}：${op[e.operator]} ${part('filler')}`
    return zh ? '组合规则' : 'Combined rule'
  }
  if (root && ['SubClassOf', 'ObjectSomeValuesFrom', 'ObjectAllValuesFrom', 'ObjectMinCardinality', 'ObjectMaxCardinality', 'ObjectExactCardinality'].includes(root.operator)) return describe(root.id)
  const names = model.nodes.filter(n => n.axiomIds.includes(ref?.id ?? axiomId)).map(n => n.label).slice(0, 2).join(' · ')
  const kinds: Record<string,string> = zh ? {Declaration:'对象定义',AnnotationAssertion:'名称与说明',ObjectPropertyDomain:'关系起点',ObjectPropertyRange:'关系终点',DataPropertyDomain:'属性所属对象',DataPropertyRange:'属性类型',SubClassOf:'分类与规则',HasKey:'识别条件'} : {Declaration:'Definition',AnnotationAssertion:'Name and description',ObjectPropertyDomain:'Relation source',ObjectPropertyRange:'Relation target',DataPropertyDomain:'Attribute owner',DataPropertyRange:'Attribute type',SubClassOf:'Classification and rules',HasKey:'Identity rule'}
  return [names,kinds[ref?.axiomType ?? ''] || (zh ? '扩展规则' : 'Extended rule')].filter(Boolean).join(' · ')
}
