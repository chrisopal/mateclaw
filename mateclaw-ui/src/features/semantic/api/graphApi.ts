import { semanticRequest } from './ontologyApi'
import type { Binding, BindRequest, EntityPage, SemanticEntity } from './types'

const kbPath = (id: string) => `/semantic/knowledge-bases/${encodeURIComponent(id)}/binding`
const graphPath = (id: string) => `/semantic/graphs/${encodeURIComponent(id)}`

export const graphApi = {
  binding: (workspaceId: string, kbId: string, signal?: AbortSignal) =>
    semanticRequest<Binding>(workspaceId, { url: kbPath(kbId) }, signal),
  bind: (workspaceId: string, kbId: string, body: BindRequest, signal?: AbortSignal) =>
    semanticRequest<Binding>(workspaceId, { url: kbPath(kbId), method: 'PUT', data: body }, signal),
  bindingsForOntology: (workspaceId: string, ontologyId: string, signal?: AbortSignal) =>
    semanticRequest<Binding[]>(workspaceId, { url: `/semantic/ontologies/${encodeURIComponent(ontologyId)}/bindings` }, signal),
  entities: (workspaceId: string, graphId: string, signal?: AbortSignal) =>
    semanticRequest<EntityPage>(workspaceId, { url: `${graphPath(graphId)}/entities` }, signal),
  createEntity: (
    workspaceId: string,
    graphId: string,
    body: Pick<SemanticEntity, 'typeKey' | 'displayName'>,
    signal?: AbortSignal,
  ) => semanticRequest<SemanticEntity>(workspaceId, { url: `${graphPath(graphId)}/entities`, method: 'POST', data: body }, signal),
}
