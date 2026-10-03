import type {
  PresalesMember,
  PresalesSource,
  PresalesTrustedStatement,
  PresalesEmployee,
  PresalesCapabilities,
  PresalesProject,
  PresalesProjectSummary,
  PresalesRepairContext,
  ProjectPage,
  PresalesHandoff,
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
  'authorId',
  'previousId',
  'answeredBy',
  'answeredAt',
  'approvedBy',
  'approvalReason',
  'publishedBy',
  'publishedAt',
  'reviewId',
  'templateVersion',
  'kind',
  'sourceSolutionId',
  'requestHash',
  'taskGoal',
  'queuedAt',
  'finishedAt',
  'modelConfigId',
  'configDigest',
  'skillName',
  'skillDigest',
  'presentationDigest',
  'workspaceId',
  'caseRef',
  'actorId',
  'skillVersion',
  'targetSolutionId',
  'adapter',
  'sha256',
  'skillSha256',
  'engineSha256',
  'engineTreeSha256',
  'inputSha256',
  'qualityReportSha256',
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
interface PendingRecord {
  value: unknown
  reference: boolean
}
function records(value: unknown, pending: PendingRecord[], reference = false) {
  array(value)
  for (const item of value) pending.push({ value: item, reference })
}
function artifact(value: unknown) {
  object(value)
  string(value.filename)
  optional(value, 'sha256', string)
  optional(value, 'size', integer)
}
/** Validate only declared record fields. Frozen snapshots and extension JSON stay opaque. */
function record(value: unknown) {
  validateRecord(value, false)
}
function validateRecord(value: unknown, reference: boolean) {
  const pending: PendingRecord[] = [{ value, reference }]
  const visited = { normal: new WeakSet<object>(), reference: new WeakSet<object>() }
  while (pending.length) {
    const item = pending.pop()!
    const current = item.value
    object(current)
    const seen = item.reference ? visited.reference : visited.normal
    if (seen.has(current)) continue
    seen.add(current)
    for (const key of recordStrings)
      optional(current, key, (value) => {
        if (item.reference && key === 'ontologyRevisionId' && value === null) return
        string(value)
      })
    for (const key of [
      'version',
      'revision',
      'baselineVersion',
      'projectVersion',
      'requirementVersion',
      'schemaVersion',
    ])
      optional(current, key, integer)
    for (const key of ['enabled', 'provisional', 'needsHumanReview'])
      optional(current, key, boolean)
    optional(current, 'sources', array)
    optional(current, 'statementRevision', (item) => {
      if (typeof item !== 'string') integer(item)
    })
    const entryReference = item.reference
    for (const key of [
      'requirementRefs',
      'evidenceIds',
      'evidenceRefs',
      'sourceRefs',
      'fitGapRefs',
    ]) {
      optional(current, key, (item) => {
        if (item === null && key === 'evidenceIds' && entryReference) return
        array(item)
        item.forEach((value) => {
          if (entryReference && key === 'evidenceIds' && value === null) return
          string(value)
        })
      })
    }
    for (const key of ['sections', 'references', 'requirementResponses', 'issues']) {
      optional(current, key, (item) => records(item, pending, key === 'references'))
    }
    optional(current, 'files', (item) => {
      array(item)
      for (const file of item) {
        object(file)
        artifact(file)
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
          artifact(slide)
          optional(slide, 'title', string)
        }
      })
      pending.push({ value: item, reference: false })
    })
    optional(current, 'coverage', (item) => {
      object(item)
      boolean(item.applicable)
      integer(item.handledIn)
      integer(item.totalIn)
      optional(item, 'percentage', (percentage) => {
        if (
          percentage !== null &&
          (typeof percentage !== 'number' ||
            !Number.isFinite(percentage) ||
            percentage < 0 ||
            percentage > 100)
        )
          invalid()
      })
      optional(item, 'responses', (responses) => records(responses, pending))
    })
    optional(current, 'contextSnapshot', (item) => {
      object(item)
      optional(item, 'truncated', boolean)
      pending.push({ value: item, reference: false })
    })
    optional(current, 'result', (item) => {
      object(item)
      for (const key of ['items', 'capabilityMaps', 'cases'])
        optional(item, key, (items) => records(items, pending))
      for (const key of ['solution', 'solutionDraft', 'review', 'reviewDraft'])
        optional(item, key, (draft) => {
          object(draft)
          pending.push({ value: draft, reference: false })
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

function nullableString(value: unknown) {
  if (value !== null) string(value)
}
function members(value: unknown, workspaceId: string): asserts value is PresalesMember[] {
  array(value)
  for (const item of value) {
    object(item)
    for (const key of ['id', 'workspaceId', 'userId', 'role']) string(item[key])
    if (item.workspaceId !== workspaceId) invalid()
    optional(item, 'nickname', nullableString)
    optional(item, 'username', nullableString)
  }
}
function sources(value: unknown): asserts value is PresalesSource[] {
  array(value)
  for (const item of value) {
    object(item)
    string(item.kbId)
    nullableString(item.name)
    const graph = Object.hasOwn(item, 'graphId'),
      revision = Object.hasOwn(item, 'ontologyRevisionId')
    if (graph !== revision) invalid()
    if (graph) {
      nullableString(item.graphId)
      nullableString(item.ontologyRevisionId)
    }
  }
}
function statements(value: unknown): asserts value is PresalesTrustedStatement[] {
  array(value)
  for (const item of value) {
    object(item)
    string(item.id)
    integer(item.revision)
    string(item.graphId)
    nullableString(item.ontologyRevisionId)
    string(item.label)
    if (item.evidenceIds !== null) {
      array(item.evidenceIds)
      item.evidenceIds.forEach(nullableString)
    }
  }
}
function employees(value: unknown): asserts value is PresalesEmployee[] {
  array(value)
  for (const item of value) {
    object(item)
    string(item.id)
    string(item.name)
    boolean(item.enabled)
    boolean(item.available)
  }
}
function capabilities(value: unknown): asserts value is PresalesCapabilities {
  object(value)
  for (const key of ['enabled', 'semanticEnabled', 'canWrite', 'canApprove']) boolean(value[key])
  optional(value, 'modelConfigured', boolean)
}
export function decodeMembers(value: unknown, workspaceId: string): PresalesMember[] {
  members(value, workspaceId)
  return value
}
export function decodeSources(value: unknown): PresalesSource[] {
  sources(value)
  return value
}
export function decodeStatements(value: unknown): PresalesTrustedStatement[] {
  statements(value)
  return value
}
export function decodeEmployees(value: unknown): PresalesEmployee[] {
  employees(value)
  return value
}
export function decodeCapabilities(value: unknown): PresalesCapabilities {
  capabilities(value)
  return value
}

/** Handoff admission preserves the original object; it does not verify artifacts or grant access. */
export function decodeHandoff(
  value: unknown,
  workspaceId: string,
  projectId: string,
): PresalesHandoff {
  handoff(value, workspaceId, projectId)
  return value
}
function handoff(
  value: unknown,
  workspaceId: string,
  projectId: string,
): asserts value is PresalesHandoff {
  object(value)
  if (value.schemaVersion !== 1) invalid()
  for (const key of [
    'workspaceId',
    'engagementId',
    'caseRef',
    'customerConfirmationStatus',
    'accessPolicy',
  ])
    string(value[key])
  if (
    value.workspaceId !== workspaceId ||
    value.engagementId !== projectId ||
    value.caseRef !== projectId
  )
    invalid()
  for (const key of ['baseline', 'solution', 'release']) entity(value[key])
  for (const key of ['fitGaps', 'clarifications']) {
    array(value[key])
    value[key].forEach(entity)
  }
  array(value.risksAndUnknowns)
  value.risksAndUnknowns.forEach(record)
  optional(value, 'releaseId', string)
  optional(value, 'historicalClarificationsAvailable', boolean)
  optional(value, 'materials', (items) => {
    array(items)
    items.forEach(entity)
  })
  optional(value, 'sourceRefs', array)
}
function entity(value: unknown) {
  object(value)
  string(value.id)
  record(value)
}
