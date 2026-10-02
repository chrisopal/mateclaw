import type {
  PresalesEditorForm,
  PresalesProject,
  PresalesRecord,
  PresalesCommandAction,
} from '../api/presalesApi'

export type PresalesEditorKind =
  | 'project'
  | 'employee'
  | 'material'
  | 'requirement'
  | 'clarification'
  | 'baseline'
  | 'fitgap'
  | 'solution'
  | 'release'
  | 'review'
  | 'context'

type ProjectMetadata = Pick<
  PresalesEditorForm,
  'name' | 'customer' | 'ownerId' | 'agentId' | 'industry' | 'goal'
>
const actions = {
  material: 'BIND_MATERIAL',
  requirement: 'SAVE_REQUIREMENT',
  clarification: 'SAVE_CLARIFICATION',
  baseline: 'APPROVE_BASELINE',
  fitgap: 'SAVE_FIT_GAP',
  solution: 'SAVE_SOLUTION',
  release: 'CREATE_RELEASE',
  review: 'SAVE_REVIEW',
  context: 'SAVE_CONTEXT',
} as const satisfies Record<
  Exclude<PresalesEditorKind, 'project' | 'employee'>,
  PresalesCommandAction
>
export type EditorSubmission =
  | { kind: 'invalid'; issue: 'REQUIRED_FIELDS' | 'ANSWER_SOURCE' }
  | { kind: 'project'; data: PresalesEditorForm; metadata: ProjectMetadata }
  | {
      kind: 'command'
      action: (typeof actions)[keyof typeof actions] | 'UPDATE_PROJECT'
      payload: PresalesEditorForm
      continueEmployee: boolean
    }

function copyForm(record: PresalesRecord): PresalesEditorForm {
  // Keep the historical JSON-copy semantics, including opaque extension fields.
  return JSON.parse(JSON.stringify(record)) as PresalesEditorForm
}

export function requirementsForBaseline(
  project: PresalesProject | null | undefined,
  baselineId: string | undefined,
): PresalesProject['requirements'] {
  const baseline = project?.baselines.find((item) => item.id === baselineId)
  return (project?.requirements || []).filter(
    (item) =>
      !baseline || baseline.references?.some((reference) => reference.requirementId === item.id),
  )
}

export function solutionResponses(
  form: PresalesEditorForm,
  project: PresalesProject | null | undefined,
): PresalesRecord[] {
  return requirementsForBaseline(project, form.baselineId).map(
    (item) =>
      form.requirementResponses?.find((response) => response.requirementId === item.id) || {
        requirementId: item.id,
        status: 'UNHANDLED',
        reason: '',
      },
  )
}

export function createEditorForm(
  kind: PresalesEditorKind,
  record: PresalesRecord | undefined,
  project: PresalesProject | null | undefined,
): PresalesEditorForm {
  if (kind === 'employee') return { agentId: record?.agentId || '' }
  const defaults: Record<PresalesEditorKind, PresalesEditorForm> = {
    project: { name: '', customer: '', ownerId: '', agentId: '', industry: '', goal: '' },
    employee: { agentId: '' },
    material: { kbId: '', graphId: '', role: 'PROJECT' },
    requirement: {
      title: '',
      description: '',
      originKind: 'INTERNAL_JUDGMENT',
      priority: 'MEDIUM',
      scope: 'IN',
      statementId: '',
      statementRevision: '',
      graphId: '',
    },
    clarification: {
      question: '',
      requirementId: '',
      impact: '',
      ownerId: '',
      answer: '',
      answerSourceId: '',
      status: 'OPEN',
    },
    solution: {
      title: '',
      baselineId: project?.baselines.at(-1)?.id || '',
      sections: [{ title: '', text: '' }],
    },
    fitgap: {
      requirementId: '',
      status: 'UNKNOWN',
      reason: '',
      evidenceText: '',
      productVersion: '',
      graphId: '',
    },
    baseline: { reason: '' },
    release: { solutionId: '', purpose: '' },
    review: { solutionId: '', summary: '', issues: [] },
    context: { title: '', text: '', originKind: 'AI_SUGGESTION', sourceRefs: [] },
  }
  const form = copyForm(record || defaults[kind])
  if (kind === 'solution') {
    delete form.id
    if (!form.requirementResponses) form.requirementResponses = solutionResponses(form, project)
  }
  return form
}

/** Prepare business intent only; the caller must still authorize and capture CAS/receipt identity. */
export function prepareEditorSubmission(
  kind: PresalesEditorKind,
  form: PresalesEditorForm,
  sourceAccessRestricted: boolean,
): EditorSubmission {
  const data = copyForm(form)
  const required: Partial<Record<PresalesEditorKind, readonly string[]>> = {
    project: ['name', 'customer'],
    material: ['kbId'],
    requirement: ['title'],
    clarification: ['question'],
    baseline: ['reason'],
    fitgap: ['requirementId'],
    solution: ['title'],
    release: ['solutionId'],
    review: ['solutionId', 'summary'],
    context: ['title', 'text'],
  }
  if (required[kind]?.some((key) => !String(data[key] || '').trim()))
    return { kind: 'invalid', issue: 'REQUIRED_FIELDS' }
  if (
    kind === 'clarification' &&
    data.status === 'ANSWERED' &&
    (!data.answer?.trim() || !data.answerSourceId?.trim())
  )
    return { kind: 'invalid', issue: 'ANSWER_SOURCE' }
  if (kind === 'project') {
    return {
      kind: 'project',
      data,
      metadata: {
        name: data.name,
        customer: data.customer,
        ownerId: data.ownerId,
        agentId: data.agentId ?? '',
        industry: data.industry,
        goal: data.goal,
      },
    }
  }
  if (kind === 'employee') {
    return {
      kind: 'command',
      action: 'UPDATE_PROJECT',
      payload: { agentId: data.agentId || '' },
      continueEmployee: false,
    }
  }
  if (kind === 'fitgap') {
    data.evidenceIds = String(data.evidenceText || '')
      .split(',')
      .map((id) => id.trim())
      .filter(Boolean)
    delete data.evidenceText
  }
  const payload =
    kind === 'material' && sourceAccessRestricted
      ? { kbId: data.kbId, graphId: data.graphId || '', role: data.role || 'PROJECT' }
      : data
  return {
    kind: 'command',
    action: actions[kind],
    payload,
    continueEmployee: kind === 'clarification' && data.status === 'ANSWERED',
  }
}
