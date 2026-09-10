import type { OntologyNodeKind, OntologyProjection } from './ontologyProjection'

export interface DisplayAxiomRef { id: string; artifactId: string; axiomId: string; imported: boolean; rendering: string; axiomType: string; status: 'FULL' | 'PARTIAL' | 'NOT_RENDERED'; reason: string }
export interface DisplayExpressionOperand { role: string; position: number; targetId?: string | null; value?: string | null }
export interface DisplayExpression { id: string; axiomId: string; path: string; operator: string; operands: DisplayExpressionOperand[] }
export interface DisplayProjection {
  schemaVersion: string; documentDigest: string; importLockDigest: string
  nodes: Array<{ id: string; iri: string; kind: OntologyNodeKind; labels: Array<{ value: string; language: string; axiomId: string }>; axiomIds: string[]; features: string[]; imported: boolean }>
  edges: OntologyProjection['edges']
  axiomRefs: DisplayAxiomRef[]
  expressions?: DisplayExpression[]
  coverage: { total: number; returned: number; truncated: boolean; dependencyScope: string; lockedImportCount: number }
}
export interface ProjectionView {
  snapshot: { ontologyId: string; revisionId: string; draftVersion: number | null; documentDigest: string; importLockDigest: string }
  projection: DisplayProjection
}

export interface DisplayExpressionOperandTree {
  operand: DisplayExpressionOperand
  label: string
  target?: DisplayProjection['nodes'][number]
  expression?: DisplayExpressionTree
}
export interface DisplayExpressionTree {
  expression: DisplayExpression
  operands: DisplayExpressionOperandTree[]
}

const expressionOperatorLabels: Record<string, [string, string]> = {
  SubClassOf: ['概念限制', 'Subclass restriction'],
  EquivalentClasses: ['等价概念', 'Equivalent classes'],
  DisjointClasses: ['互斥概念', 'Disjoint classes'],
  ObjectIntersectionOf: ['交集', 'Object intersection'],
  ObjectUnionOf: ['并集', 'Object union'],
  ObjectComplementOf: ['补集', 'Object complement'],
  ObjectSomeValuesFrom: ['存在限制', 'Some values from'],
  ObjectAllValuesFrom: ['全称限制', 'All values from'],
  ObjectMinCardinality: ['最小基数', 'Minimum cardinality'],
  ObjectMaxCardinality: ['最大基数', 'Maximum cardinality'],
  ObjectExactCardinality: ['精确基数', 'Exact cardinality'],
  ObjectHasValue: ['指定值', 'Has value'],
  ObjectHasSelf: ['自身关系', 'Has self'],
  ObjectOneOf: ['个体枚举', 'One of'],
  ObjectInverseOf: ['逆属性', 'Inverse property'],
  DataSomeValuesFrom: ['数据存在限制', 'Data some values from'],
  DataAllValuesFrom: ['数据全称限制', 'Data all values from'],
  DataMinCardinality: ['数据最小基数', 'Data minimum cardinality'],
  DataMaxCardinality: ['数据最大基数', 'Data maximum cardinality'],
  DataExactCardinality: ['数据精确基数', 'Data exact cardinality'],
  DataHasValue: ['数据指定值', 'Data has value'],
  DataOneOf: ['数据枚举', 'Data one of'],
  DataIntersectionOf: ['数据交集', 'Data intersection'], DataUnionOf: ['数据并集', 'Data union'], DataComplementOf: ['数据补集', 'Data complement'],
  ObjectPropertyDomain: ['关系定义域', 'Object property domain'], ObjectPropertyRange: ['关系值域', 'Object property range'], DataPropertyDomain: ['属性定义域', 'Data property domain'], DataPropertyRange: ['属性值域', 'Data property range'],
  ObjectPropertyChain: ['属性链', 'Property chain'],
  SubPropertyChainOf: ['属性链', 'Property chain'],
  SubObjectPropertyOf: ['子属性限制', 'Sub object property'],
}
const expressionRoleLabels: Record<string, [string, string]> = {
  subClass: ['子概念', 'Subclass'], superClass: ['上位概念或限制', 'Superclass or restriction'], member: ['成员', 'Member'], step: ['步骤', 'Step'], domain: ['定义域', 'Domain'], range: ['值域', 'Range'],
  subject: ['主体', 'Subject'], property: ['属性', 'Property'], filler: ['填充概念', 'Filler'],
  cardinality: ['基数', 'Cardinality'], value: ['值', 'Value'], operand: ['操作数', 'Operand'],
  chain: ['链成员', 'Chain member'], class: ['概念', 'Class'], datatype: ['数据类型', 'Datatype'],
  minCardinality: ['最小基数', 'Minimum cardinality'], maxCardinality: ['最大基数', 'Maximum cardinality'],
  exactCardinality: ['精确基数', 'Exact cardinality'], subProperty: ['子属性', 'Sub-property'],
  superProperty: ['父属性', 'Super-property'],
}
export function expressionOperatorLabel(operator: string, language: string): string {
  const [zh, en] = expressionOperatorLabels[operator] ?? [operator, operator]
  return language.toLowerCase().startsWith('zh') ? `${zh} · ${operator}` : `${en} · ${operator}`
}
export function expressionRoleLabel(role: string, language: string): string {
  const [zh, en] = expressionRoleLabels[role] ?? [role, role]
  return language.toLowerCase().startsWith('zh') ? zh : en
}

/** Build a read-only tree from server references; no Functional Syntax parsing is involved. */
export function expressionTrees(projection: DisplayProjection | null | undefined, language = ''): DisplayExpressionTree[] {
  const expressions = projection?.expressions ?? []
  if (!expressions.length) return []
  const byId = new Map(expressions.map(expression => [expression.id, expression]))
  const referenced = new Set(expressions.flatMap(expression => expression.operands.map(operand => operand.targetId).filter((id): id is string => !!id && byId.has(id))))
  const nodes = new Map(projection?.nodes.flatMap(node => [[node.id, node], [node.iri, node]] as const) ?? [])
  const axiomOrder = new Map(projection?.axiomRefs.flatMap((ref, index) => [[ref.id, index], [ref.axiomId, index]] as const) ?? [])
  const build = (expression: DisplayExpression, seen: Set<string>): DisplayExpressionTree => ({
    expression,
    operands: [...expression.operands].sort((a, b) => a.position - b.position).map(operand => {
      const nested = operand.targetId ? byId.get(operand.targetId) : undefined
      const target = operand.targetId ? nodes.get(operand.targetId) : undefined
      const label = target ? [...target.labels].sort((a, b) => {
        const score = (value: string) => { const candidate = value.toLowerCase(); const preferred = language.toLowerCase(); return candidate === preferred ? 0 : candidate && candidate.split('-')[0] === preferred.split('-')[0] ? 1 : !candidate ? 2 : 3 }
        return score(a.language) - score(b.language)
      })[0]?.value || target.iri.split(/[#/:]/).pop() || target.iri : operand.value || operand.targetId || '—'
      return { operand, label, target, expression: nested && !seen.has(nested.id) ? build(nested, new Set([...seen, nested.id])) : undefined }
    }),
  })
  const roots = [...expressions].filter(expression => !referenced.has(expression.id))
  return (roots.length ? roots : [...expressions])
    .sort((a, b) => (axiomOrder.get(a.axiomId) ?? Number.MAX_SAFE_INTEGER) - (axiomOrder.get(b.axiomId) ?? Number.MAX_SAFE_INTEGER) || a.axiomId.localeCompare(b.axiomId) || a.path.localeCompare(b.path) || a.id.localeCompare(b.id))
    .map(expression => build(expression, new Set([expression.id])))
}
export function displayModel(projection: DisplayProjection | null | undefined, language: string): OntologyProjection {
  if (!projection) return { nodes: [], edges: [], unprojectedAxiomIds: [] }
  const preferred=language.toLowerCase()
  const score=(lang:string)=>{const candidate=lang.toLowerCase();return candidate===preferred?0:candidate&&candidate.split('-')[0]===preferred.split('-')[0]?1:!candidate?2:3}
  return {
    nodes: projection.nodes.map(n=>({...n,label:[...n.labels].sort((a,b)=>score(a.language)-score(b.language))[0]?.value || n.iri.split(/[#/:]/).pop() || n.iri})),
    edges: projection.edges,
    unprojectedAxiomIds: projection.axiomRefs.filter(a=>a.status!=='FULL').map(a=>a.id),
  }
}
