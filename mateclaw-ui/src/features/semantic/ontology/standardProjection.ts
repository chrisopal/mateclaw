import type { OntologyNodeKind, OntologyProjection } from './ontologyProjection'

export interface DisplayAxiomRef { id: string; artifactId: string; axiomId: string; imported: boolean; rendering: string; axiomType: string; status: 'FULL' | 'PARTIAL' | 'NOT_RENDERED'; reason: string }
export interface DisplayProjection {
  schemaVersion: string; documentDigest: string; importLockDigest: string
  nodes: Array<{ id: string; iri: string; kind: OntologyNodeKind; labels: Array<{ value: string; language: string; axiomId: string }>; axiomIds: string[]; features: string[]; imported: boolean }>
  edges: OntologyProjection['edges']
  axiomRefs: DisplayAxiomRef[]
  coverage: { total: number; returned: number; truncated: boolean; dependencyScope: string; lockedImportCount: number }
}
export interface ProjectionView {
  snapshot: { ontologyId: string; revisionId: string; draftVersion: number | null; documentDigest: string; importLockDigest: string }
  projection: DisplayProjection
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
