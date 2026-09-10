import { semanticRequest } from './ontologyApi'
export type MappingRole = 'classes' | 'objectProperties' | 'dataProperties' | 'individuals'
export interface IriMapping { from: string; to: string }
export interface MigrationPrepare { operationId: string; sourceRevisionId: string; targetRevisionId: string; expectedGraphVersion: number; classes: IriMapping[]; objectProperties: IriMapping[]; dataProperties: IriMapping[]; individuals: IriMapping[] }
export interface MigrationBlocker { code: string; path: string; message: string }
export interface MigrationPlan {
  id: string; graphId: string; sourceRevisionId: string; targetRevisionId: string; sourceVersion: number; targetVersion: number
  status: string; planDigest: string; expectedGraphVersion: number; executedGraphVersion: number
  impact: { entities: number; facts: number; changedEntities: number; changedFacts: number; blockers: MigrationBlocker[]; truncated: boolean }
  entities: { entityId: string; oldIri: string; targetIri: string; oldTypes: string[]; targetTypes: string[]; blockers: MigrationBlocker[] }[]
  facts: { statementId: string; sourceRevision: number; sourceAssertionText: string; targetAssertionText: string; status: string; blockers: MigrationBlocker[] }[]
}
export interface MigrationTarget { id: string; version: number; name: string }
const path = (graph: string) => `/semantic/graphs/${encodeURIComponent(graph)}/migrations/owl`
export const migrationApi = {
  targets: (ws: string, graph: string, signal?: AbortSignal) => semanticRequest<MigrationTarget[]>(ws, { url: `${path(graph)}/targets` }, signal),
  prepare: (ws: string, graph: string, body: MigrationPrepare, signal?: AbortSignal) => semanticRequest<MigrationPlan>(ws, { url: `${path(graph)}/prepare`, method: 'POST', data: body }, signal),
  get: (ws: string, graph: string, id: string, signal?: AbortSignal) => semanticRequest<MigrationPlan>(ws, { url: `${path(graph)}/${encodeURIComponent(id)}` }, signal),
  act: (ws: string, graph: string, id: string, action: 'approve' | 'execute' | 'rollback', body: { operationId: string; expectedPlanDigest: string; expectedGraphVersion?: number }, signal?: AbortSignal) => semanticRequest<MigrationPlan>(ws, { url: `${path(graph)}/${encodeURIComponent(id)}/${action}`, method: 'POST', data: body }, signal),
}
