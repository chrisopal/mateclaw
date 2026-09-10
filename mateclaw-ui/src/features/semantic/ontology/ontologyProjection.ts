import type { AxiomDescriptor } from '../api/types'

export type OntologyNodeKind = 'class' | 'objectProperty' | 'dataProperty' | 'individual' | 'datatype' | 'annotationProperty'
export interface OntologyProjectionNode { id: string; iri: string; label: string; kind: OntologyNodeKind; axiomIds: string[] }
export interface OntologyProjectionEdge { id: string; source: string; target: string; label: string; axiomId: string; kind: 'subClassOf' | 'domain' | 'range' | 'someValuesFrom' | 'allValuesFrom' }
export interface OntologyProjection { nodes: OntologyProjectionNode[]; edges: OntologyProjectionEdge[]; unprojectedAxiomIds: string[] }
type Term = string | { name: string; args: Term[] }
const kinds: Record<string, OntologyNodeKind> = { Class: 'class', ObjectProperty: 'objectProperty', DataProperty: 'dataProperty', NamedIndividual: 'individual', Datatype: 'datatype', AnnotationProperty: 'annotationProperty' }
const prefixes: Record<string, string> = { owl: 'http://www.w3.org/2002/07/owl#', rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#', rdfs: 'http://www.w3.org/2000/01/rdf-schema#', xsd: 'http://www.w3.org/2001/XMLSchema#' }
function shortIri(value: string): string { return value.split(/[#/:]/).pop() || value }
function iri(term: Term | undefined): string | undefined {
  if (typeof term !== 'string') return undefined
  if (/^<[^<>\s]+>$/.test(term)) return term.slice(1, -1)
  const match = /^([a-z]+):([\w.-]+)$/.exec(term)
  return match && prefixes[match[1]!] ? prefixes[match[1]!] + match[2] : undefined
}

// Deliberately bounded display parser. Unsupported expressions stay in the axiom list.
function parse(text: string): Term | undefined {
  if (text.length > 100_000) return undefined
  const tokens = text.match(/"(?:\\.|[^"\\])*"(?:@[\w-]+|\^\^(?:<[^<>\s]+>|[\w.-]+:[\w.-]+))?|<[^<>\s]+>|[()]|[^\s()<>" ]+/g) ?? []
  let offset = 0
  for (const token of tokens) {
    while (offset < text.length && /\s/.test(text[offset]!)) offset++
    if (!text.startsWith(token, offset)) return undefined
    offset += token.length
  }
  if (text.slice(offset).trim()) return undefined
  let cursor = 0
  function read(depth: number): Term | undefined {
    if (depth > 64) return undefined
    const name = tokens[cursor++]
    if (!name || name === '(' || name === ')') return undefined
    if (tokens[cursor] !== '(') return name
    cursor++
    const args: Term[] = []
    while (cursor < tokens.length && tokens[cursor] !== ')') { const term = read(depth + 1); if (term === undefined) return undefined; args.push(term) }
    if (tokens[cursor++] !== ')') return undefined
    return { name, args }
  }
  const root = read(0)
  return cursor === tokens.length ? root : undefined
}
function operands(term: Exclude<Term, string>): Term[] {
  let start = 0
  while (typeof term.args[start] === 'object' && (term.args[start] as Exclude<Term, string>).name === 'Annotation') start++
  return term.args.slice(start)
}
function literal(term: Term | undefined): string | undefined {
  if (typeof term !== 'string') return undefined
  const match = /^"((?:\\.|[^"\\])*)"(?:@[\w-]+|\^\^(?:<[^<>\s]+>|[\w.-]+:[\w.-]+))?$/.exec(term)
  if (!match || /\\[^"\\]/.test(match[1]!)) return undefined
  return match[1]!.replace(/\\(["\\])/g, '$1')
}
export function projectOntology(axioms: AxiomDescriptor[]): OntologyProjection {
  const nodes = new Map<string, OntologyProjectionNode>()
  const edges: OntologyProjectionEdge[] = []
  const restrictionProperties = new Map<string, string>()
  const unprojectedAxiomIds: string[] = []
  const labels = new Map<string, { label: string; axiomIds: string[] }>()
  function node(value: string, kind: OntologyNodeKind, axiomId: string): OntologyProjectionNode {
    const id = `${kind}:${value}`
    let result = nodes.get(id)
    if (!result) { result = { id, iri: value, label: shortIri(value), kind, axiomIds: [] }; nodes.set(id, result) }
    if (!result.axiomIds.includes(axiomId)) result.axiomIds.push(axiomId)
    return result
  }
  for (const axiom of axioms) {
    const root = parse(axiom.rendering)
    let projected = false
    if (root && typeof root !== 'string') {
      const args = operands(root)
      if (root.name === 'Declaration' && args.length === 1 && typeof args[0] === 'object') {
        const declaration = args[0]; const kind = kinds[declaration.name]; const value = iri(declaration.args[0])
        if (kind && value && declaration.args.length === 1) { node(value, kind, axiom.axiomId); projected = true }
      } else if (root.name === 'AnnotationAssertion' && args.length === 3 && iri(args[0]) === `${prefixes.rdfs}label`) {
        const subject = iri(args[1]); const label = literal(args[2])
        if (subject && label !== undefined) { const previous = labels.get(subject); labels.set(subject, { label: previous?.label ?? label, axiomIds: [...(previous?.axiomIds ?? []), axiom.axiomId] }); projected = true }
      } else if (args.length === 2) {
        const first = iri(args[0]); const second = iri(args[1])
        const restriction = args[1]
        if (root.name === 'SubClassOf' && first && typeof restriction === 'object' && ['ObjectSomeValuesFrom', 'ObjectAllValuesFrom'].includes(restriction.name) && restriction.args.length === 2) {
          const property = iri(restriction.args[0]); const filler = iri(restriction.args[1])
          if (property && filler) {
            const kind = restriction.name === 'ObjectSomeValuesFrom' ? 'someValuesFrom' : 'allValuesFrom'
            const source = node(first, 'class', axiom.axiomId); const target = node(filler, 'class', axiom.axiomId)
            const id = `${axiom.axiomId}:${kind}`
            edges.push({ id, source: source.id, target: target.id, kind, label: '', axiomId: axiom.axiomId })
            restrictionProperties.set(id, property)
            // The edge displays a quantified class constraint, never an individual fact.
            // Retain annotated axioms for inspecting the additional annotation content.
            projected = root.args.length === args.length && axiom.annotations.length === 0
          }
        } else if (first && second) {
          let source: OntologyProjectionNode | undefined; let target: OntologyProjectionNode | undefined; let kind: OntologyProjectionEdge['kind'] = 'subClassOf'
          if (root.name === 'SubClassOf') { source = node(first, 'class', axiom.axiomId); target = node(second, 'class', axiom.axiomId) }
          else if (/^(Object|Data)Property(Domain|Range)$/.test(root.name)) {
            const property = node(first, root.name.startsWith('Object') ? 'objectProperty' : 'dataProperty', axiom.axiomId)
            kind = root.name.endsWith('Domain') ? 'domain' : 'range'
            const endpoint = node(second, kind === 'range' && root.name.startsWith('Data') ? 'datatype' : 'class', axiom.axiomId)
            source = kind === 'domain' ? endpoint : property; target = kind === 'domain' ? property : endpoint
          }
          if (source && target) { edges.push({ id: `${axiom.axiomId}:${kind}`, source: source.id, target: target.id, kind, label: { subClassOf: '子类属于', domain: '定义域', range: '值域' }[kind], axiomId: axiom.axiomId }); projected = true }
        }
      }
    }
    if (!projected) unprojectedAxiomIds.push(axiom.axiomId)
  }
  for (const entry of nodes.values()) { const label = labels.get(entry.iri); if (label) { entry.label = label.label; entry.axiomIds = [...new Set([...entry.axiomIds, ...label.axiomIds])] } }
  for (const edge of edges) {
    const property = restrictionProperties.get(edge.id)
    if (property) edge.label = `${labels.get(property)?.label ?? shortIri(property)} · ${edge.kind === 'someValuesFrom' ? '至少一个' : '仅限'}`
  }
  // Label-only subjects have no known entity kind and cannot become invented nodes.
  for (const [subject, label] of labels) if (![...nodes.values()].some(entry => entry.iri === subject) && ![...restrictionProperties.values()].includes(subject)) unprojectedAxiomIds.push(...label.axiomIds)
  return { nodes: [...nodes.values()], edges, unprojectedAxiomIds }
}
