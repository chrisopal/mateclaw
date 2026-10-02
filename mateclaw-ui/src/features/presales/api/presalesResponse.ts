import type {
  PresalesProject,
  PresalesProjectSummary,
  PresalesRepairContext,
  ProjectPage,
} from './presalesApi'

const collections = [
  'materials',
  'requirements',
  'clarifications',
  'baselines',
  'fitGaps',
  'cases',
  'solutions',
  'reviews',
  'reviewDrafts',
  'releases',
  'tasks',
  'contextCards',
] as const
const requiredCollections = [
  'materials',
  'requirements',
  'clarifications',
  'baselines',
  'fitGaps',
  'solutions',
  'reviews',
  'releases',
] as const
const metadata = [
  'id',
  'workspaceId',
  'version',
  'name',
  'customer',
  'ownerId',
  'industry',
  'goal',
  'status',
  'stage',
  'agentId',
  'agentName',
  'createdBy',
  'createdAt',
  'updatedBy',
  'updatedAt',
]
const recordStrings = [
  'id',
  'createdBy',
  'createdAt',
  'updatedBy',
  'updatedAt',
  'artifactId',
  'name',
  'title',
  'text',
  'status',
  'role',
  'description',
  'originKind',
  'priority',
  'scope',
  'authority',
  'customerConfirmationStatus',
  'userId',
  'nickname',
  'username',
  'kbId',
  'graphId',
  'ontologyRevisionId',
  'label',
  'requirementId',
  'statementId',
  'proposedByTaskId',
  'question',
  'impact',
  'ownerId',
  'answer',
  'answerSourceId',
  'reason',
  'evidenceText',
  'productVersion',
  'baselineId',
  'solutionId',
  'purpose',
  'summary',
  'severity',
  'operationId',
  'skill',
  'conversationId',
  'agentId',
  'agentName',
  'runId',
  'queueState',
  'error',
]

function invalid(): never {
  throw Object.assign(new Error('Unable to read project data. Reload and try again.'), {
    code: 'PRESALES_RESPONSE_INVALID',
  })
}
function object(value: unknown): asserts value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) invalid()
}
function string(value: unknown): asserts value is string {
  if (typeof value !== 'string') invalid()
}
function integer(value: unknown, minimum = 0): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) invalid()
}
function boolean(value: unknown): asserts value is boolean {
  if (typeof value !== 'boolean') invalid()
}
function array(value: unknown): asserts value is unknown[] {
  if (!Array.isArray(value)) invalid()
}
function optional(value: Record<string, unknown>, key: string, check: (item: unknown) => void) {
  if (Object.hasOwn(value, key)) check(value[key])
}
function records(value: unknown, pending: unknown[]) {
  array(value)
  for (const item of value) pending.push(item)
}
/** Validate only declared record fields. Frozen snapshots and extension JSON stay opaque. */
function record(value: unknown) {
  const pending: unknown[] = [value]
  const visited = new WeakSet<object>()
  while (pending.length) {
    const current = pending.pop()
    object(current)
    if (visited.has(current)) continue
    visited.add(current)
    for (const key of recordStrings) optional(current, key, string)
    for (const key of ['version', 'revision']) optional(current, key, integer)
    optional(current, 'enabled', boolean)
    optional(current, 'statementRevision', (item) => {
      if (typeof item !== 'string') integer(item)
    })
    for (const key of ['requirementRefs', 'evidenceIds', 'evidenceRefs', 'sourceRefs']) {
      optional(current, key, (item) => {
        array(item)
        item.forEach(string)
      })
    }
    for (const key of ['sections', 'references', 'requirementResponses', 'issues']) {
      optional(current, key, (item) => records(item, pending))
    }
    optional(current, 'files', (item) => {
      array(item)
      for (const file of item) {
        object(file)
        string(file.filename)
      }
    })
    optional(current, 'presentation', (item) => {
      object(item)
      string(item.artifactId)
      string(item.skill)
      string(item.skillVersion)
      integer(item.pageCount)
      optional(item, 'slides', (slides) => {
        array(slides)
        for (const slide of slides) {
          object(slide)
          string(slide.filename)
          optional(slide, 'title', string)
        }
      })
      pending.push(item)
    })
    optional(current, 'coverage', (item) => {
      object(item)
      boolean(item.applicable)
      integer(item.handledIn)
      integer(item.totalIn)
      optional(item, 'responses', (responses) => records(responses, pending))
    })
    optional(current, 'contextSnapshot', (item) => {
      object(item)
      optional(item, 'truncated', boolean)
      pending.push(item)
    })
    optional(current, 'result', (item) => {
      object(item)
      optional(item, 'items', (items) => records(items, pending))
      optional(item, 'solution', (solution) => {
        object(solution)
        pending.push(solution)
      })
    })
  }
}
function summary(
  value: unknown,
  workspaceId: string,
  projectId?: string,
): asserts value is PresalesProjectSummary {
  object(value)
  for (const key of ['id', 'workspaceId', 'name', 'customer', 'ownerId', 'status'])
    string(value[key])
  integer(value.version, 1)
  if (value.workspaceId !== workspaceId || (projectId !== undefined && value.id !== projectId))
    invalid()
  for (const key of ['industry', 'goal', 'stage']) optional(value, key, string)
  for (const key of ['openClarificationCount', 'latestSolutionVersion'])
    optional(value, key, integer)
  record(value)
}
function bindings(value: unknown, exact = false) {
  array(value)
  for (const binding of value) {
    object(binding)
    string(binding.id)
    string(binding.role)
    if (exact && Object.keys(binding).some((key) => key !== 'id' && key !== 'role')) invalid()
  }
}
function repair(
  value: unknown,
  workspaceId: string,
  projectId: string,
): asserts value is PresalesRepairContext {
  summary(value, workspaceId, projectId)
  if (value.sourceAccessRestricted !== true) invalid()
  const allowed = new Set([...metadata, ...collections, 'sourceAccessRestricted', 'repairBindings'])
  if (Object.keys(value).some((key) => !allowed.has(key))) invalid()
  for (const key of collections) {
    array(value[key])
    if (value[key].length !== 0) invalid()
  }
  bindings(value.repairBindings, true)
}
function project(
  value: unknown,
  workspaceId: string,
  projectId?: string,
): asserts value is PresalesProject {
  summary(value, workspaceId, projectId)
  optional(value, 'sourceAccessRestricted', boolean)
  if (value.sourceAccessRestricted === true) {
    repair(value, workspaceId, value.id)
    return
  }
  optional(value, 'repairBindings', bindings)
  for (const key of requiredCollections) array(value[key])
  for (const key of collections)
    optional(value, key, (items) => {
      array(items)
      for (const item of items) {
        object(item)
        string(item.id)
        if (key === 'tasks') string(item.status)
        record(item)
      }
    })
}
function page(value: unknown, workspaceId: string): asserts value is ProjectPage {
  object(value)
  array(value.items)
  for (const item of value.items) {
    summary(item, workspaceId)
    if (collections.some((key) => Object.hasOwn(item, key))) invalid()
  }
  if (typeof value.total === 'string') {
    if (!/^(0|[1-9][0-9]*)$/.test(value.total)) invalid()
    integer(Number(value.total))
  } else integer(value.total)
  integer(value.page, 1)
  integer(value.pageSize, 1)
  if (value.pageSize > 100) invalid()
}
export function decodeProject(
  value: unknown,
  workspaceId: string,
  projectId?: string,
): PresalesProject {
  project(value, workspaceId, projectId)
  return value
}
export function decodeProjectPage(value: unknown, workspaceId: string): ProjectPage {
  page(value, workspaceId)
  return value
}
export function decodeRepairContext(
  value: unknown,
  workspaceId: string,
  projectId: string,
): PresalesRepairContext {
  repair(value, workspaceId, projectId)
  return value
}
