import dagre from 'dagre'
import type { OntologyNodeKind, OntologyProjection, OntologyProjectionEdge } from './ontologyProjection'

export interface GraphViewOptions {
  query?: string
  mode?: 'schema' | 'individuals'
  onlyMatches?: boolean
  neighborId?: string
  nodeKind?: OntologyNodeKind | ''
  edgeKind?: OntologyProjectionEdge['kind'] | ''
  limit?: number
}

/** Display-only selection. Never infer relationships from signatures or labels. */
export function graphView(model: OntologyProjection, options: GraphViewOptions = {}) {
  const query = options.query?.trim().toLocaleLowerCase() ?? ''
  const candidates = model.nodes.filter(n => (options.mode === 'individuals' ? n.kind === 'individual' : n.kind !== 'individual') && (!options.nodeKind || n.kind === options.nodeKind))
  const matches = candidates.filter(n => query && `${n.label} ${n.iri}`.toLocaleLowerCase().includes(query)).map(n => n.id)
  const matchSet = new Set(matches)
  const edges = model.edges.filter(e => !options.edgeKind || e.kind === options.edgeKind)
  const neighbors = new Set([options.neighborId])
  if (options.neighborId) for (const edge of edges) {
    if (edge.source === options.neighborId) neighbors.add(edge.target)
    if (edge.target === options.neighborId) neighbors.add(edge.source)
  }
  const eligible = candidates.filter(n => (!query || !options.onlyMatches || matchSet.has(n.id)) && (!options.neighborId || neighbors.has(n.id)))
  const limit = Math.max(1, Math.min(500, options.limit ?? 500))
  // For large ontologies, search can bring a node outside the initial display window into view.
  const ordered = eligible.length > limit ? [...eligible.filter(n => matchSet.has(n.id)), ...eligible.filter(n => !matchSet.has(n.id))] : eligible
  const nodes = ordered.slice(0, limit)
  const visible = new Set(nodes.map(n => n.id))
  return { nodes, edges: edges.filter(e => visible.has(e.source) && visible.has(e.target)), matches, hiddenNodes: model.nodes.length - nodes.length, truncated: eligible.length > limit }
}

export function hierarchyPositions(nodes: OntologyProjection['nodes'], edges: OntologyProjection['edges']) {
  const graph = new dagre.graphlib.Graph({ multigraph: true })
    .setGraph({ rankdir: 'TB', nodesep: 35, ranksep: 65, marginx: 25, marginy: 25 })
    .setDefaultEdgeLabel(() => ({}))
  nodes.forEach(n => graph.setNode(n.id, { width: 176, height: 48 }))
  edges.forEach(e => graph.setEdge(e.source, e.target, {}, e.id))
  dagre.layout(graph)
  return new Map(nodes.map(n => [n.id, { x: graph.node(n.id).x, y: graph.node(n.id).y }]))
}
