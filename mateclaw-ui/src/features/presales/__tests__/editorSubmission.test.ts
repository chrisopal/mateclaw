import { describe, expect, it } from 'vitest'
import {
  createEditorForm,
  prepareEditorSubmission,
  requirementsForBaseline,
  solutionResponses,
} from '../shared/editorSubmission'
import type { PresalesEditorKind } from '../shared/editorSubmission'
import type { PresalesEditorForm, PresalesProject } from '../api/presalesApi'

const project: PresalesProject = {
  id: '9223372036854775800',
  workspaceId: '9223372036854775801',
  version: 7,
  name: 'Factory',
  customer: 'Customer',
  ownerId: '9223372036854775802',
  status: 'ACTIVE',
  materials: [],
  clarifications: [],
  fitGaps: [],
  solutions: [],
  reviews: [],
  releases: [],
  requirements: [
    { id: 'req-1', title: 'One' },
    { id: 'req-2', title: 'Two' },
  ],
  baselines: [
    { id: 'baseline-1', references: [{ requirementId: 'req-1' }] },
    { id: 'baseline-2', references: [{ requirementId: 'req-2' }] },
  ],
}
const defaults: [PresalesEditorKind, PresalesEditorForm][] = [
  ['project', { name: '', customer: '', ownerId: '', agentId: '', industry: '', goal: '' }],
  ['employee', { agentId: '' }],
  ['material', { kbId: '', graphId: '', role: 'PROJECT' }],
  [
    'requirement',
    {
      title: '',
      description: '',
      originKind: 'INTERNAL_JUDGMENT',
      priority: 'MEDIUM',
      scope: 'IN',
      statementId: '',
      statementRevision: '',
      graphId: '',
    },
  ],
  [
    'clarification',
    {
      question: '',
      requirementId: '',
      impact: '',
      ownerId: '',
      answer: '',
      answerSourceId: '',
      status: 'OPEN',
    },
  ],
  ['baseline', { reason: '' }],
  [
    'fitgap',
    {
      requirementId: '',
      status: 'UNKNOWN',
      reason: '',
      evidenceText: '',
      productVersion: '',
      graphId: '',
    },
  ],
  [
    'solution',
    {
      title: '',
      baselineId: 'baseline-2',
      sections: [{ title: '', text: '' }],
      requirementResponses: [{ requirementId: 'req-2', status: 'UNHANDLED', reason: '' }],
    },
  ],
  ['release', { solutionId: '', purpose: '' }],
  ['review', { solutionId: '', summary: '', issues: [] }],
  ['context', { title: '', text: '', originKind: 'AI_SUGGESTION', sourceRefs: [] }],
]

describe('editor initialization and requirement alignment', () => {
  it.each(defaults)('keeps %s defaults without mutating project', (kind, expected) => {
    const before = structuredClone(project)
    expect(createEditorForm(kind, undefined, project)).toEqual(expected)
    expect(project).toEqual(before)
  })
  it('keeps independent nested drafts and JSON-copied historical extension fields', () => {
    const record = {
      id: '9223372036854775800',
      title: 'Historical',
      extra: { nested: ['evidence-1'] },
      omitted: undefined,
    }
    const copy = createEditorForm('requirement', record, project)
    expect(copy).toEqual({ id: record.id, title: 'Historical', extra: { nested: ['evidence-1'] } })
    expect(copy.extra).not.toBe(record.extra)
    const first = createEditorForm('context', undefined, project)
    first.sourceRefs!.push('changed')
    expect(createEditorForm('context', undefined, project).sourceRefs).toEqual([])
  })
  it('limits employee initialization to agent identity', () => {
    expect(
      createEditorForm(
        'employee',
        { agentId: '9223372036854775800', name: 'hidden', sourceSnapshot: { private: true } },
        project,
      ),
    ).toEqual({ agentId: '9223372036854775800' })
  })
  it('creates a new solution identity while preserving revision content and explicitly empty responses', () => {
    const record = {
      id: 'old-solution',
      version: 4,
      baselineId: 'baseline-1',
      sections: [{ text: 'exact text' }],
      requirementResponses: [],
    }
    const copy = createEditorForm('solution', record, project)
    expect(copy).toEqual({
      version: 4,
      baselineId: 'baseline-1',
      sections: [{ text: 'exact text' }],
      requirementResponses: [],
    })
    expect(record.id).toBe('old-solution')
    expect(copy.sections).not.toBe(record.sections)
  })
  it('keeps known baseline membership, missing-baseline fallback and missing references semantics', () => {
    expect(requirementsForBaseline(project, 'baseline-1').map((item) => item.id)).toEqual(['req-1'])
    expect(requirementsForBaseline(project, 'unknown').map((item) => item.id)).toEqual([
      'req-1',
      'req-2',
    ])
    expect(requirementsForBaseline({ ...project, baselines: [{ id: 'empty' }] }, 'empty')).toEqual(
      [],
    )
    expect(requirementsForBaseline(null, undefined)).toEqual([])
  })
  it('retains matching response content, drops unselected requirements and initializes new members', () => {
    const kept = {
      requirementId: 'req-2',
      status: 'FIT',
      reason: 'verified',
      evidenceIds: ['9223372036854775800'],
    }
    const form = {
      baselineId: 'baseline-2',
      requirementResponses: [{ requirementId: 'req-1', status: 'GAP' }, kept],
    }
    expect(solutionResponses(form, project)).toEqual([kept])
    expect(solutionResponses(form, project)[0]).toBe(kept)
    expect(solutionResponses({ ...form, baselineId: 'baseline-1' }, project)).toEqual([
      { requirementId: 'req-1', status: 'GAP' },
    ])
    expect(solutionResponses({ baselineId: 'unknown' }, project)).toEqual([
      { requirementId: 'req-1', status: 'UNHANDLED', reason: '' },
      { requirementId: 'req-2', status: 'UNHANDLED', reason: '' },
    ])
  })
})

describe('business submission contracts', () => {
  it.each([
    'project',
    'material',
    'requirement',
    'clarification',
    'baseline',
    'fitgap',
    'solution',
    'release',
    'review',
    'context',
  ] as const)('rejects missing required %s fields before any command exists', (kind) => {
    expect(prepareEditorSubmission(kind, {}, false)).toEqual({
      kind: 'invalid',
      issue: 'REQUIRED_FIELDS',
    })
  })
  it('validates whitespace without trimming accepted storage values or expanding project metadata', () => {
    expect(prepareEditorSubmission('project', { name: ' ', customer: 'Customer' }, false)).toEqual({
      kind: 'invalid',
      issue: 'REQUIRED_FIELDS',
    })
    const form = {
      name: '  Factory  ',
      customer: '  Customer  ',
      ownerId: project.ownerId,
      extension: { exact: true },
      agentId: undefined,
    }
    expect(prepareEditorSubmission('project', form, false)).toEqual({
      kind: 'project',
      data: {
        name: form.name,
        customer: form.customer,
        ownerId: form.ownerId,
        extension: form.extension,
      },
      metadata: {
        name: form.name,
        customer: form.customer,
        ownerId: form.ownerId,
        agentId: '',
        industry: undefined,
        goal: undefined,
      },
    })
    expect(form.agentId).toBeUndefined()
  })
  it('limits employee updates and retains unassignment', () => {
    expect(
      prepareEditorSubmission(
        'employee',
        { agentId: project.ownerId, name: 'ignored', sourceSnapshot: 'secret' },
        true,
      ),
    ).toEqual({
      kind: 'command',
      action: 'UPDATE_PROJECT',
      payload: { agentId: project.ownerId },
      continueEmployee: false,
    })
    expect(prepareEditorSubmission('employee', {}, true)).toEqual({
      kind: 'command',
      action: 'UPDATE_PROJECT',
      payload: { agentId: '' },
      continueEmployee: false,
    })
  })
  it('limits only restricted material payloads without treating mapping as authorization', () => {
    const form = {
      kbId: project.id,
      graphId: 'graph/1',
      role: '',
      name: 'hidden',
      sourceSnapshot: { private: true },
    }
    expect(prepareEditorSubmission('material', form, true)).toEqual({
      kind: 'command',
      action: 'BIND_MATERIAL',
      payload: { kbId: project.id, graphId: 'graph/1', role: 'PROJECT' },
      continueEmployee: false,
    })
    expect(prepareEditorSubmission('material', form, false)).toEqual({
      kind: 'command',
      action: 'BIND_MATERIAL',
      payload: form,
      continueEmployee: false,
    })
  })
  it('keeps clarification validation order and requires both answer and source for continuation', () => {
    expect(prepareEditorSubmission('clarification', { status: 'ANSWERED' }, false)).toEqual({
      kind: 'invalid',
      issue: 'REQUIRED_FIELDS',
    })
    for (const form of [
      { question: 'Q', status: 'ANSWERED', answer: 'A' },
      { question: 'Q', status: 'ANSWERED', answer: ' ', answerSourceId: 'source' },
    ]) {
      expect(prepareEditorSubmission('clarification', form, false)).toEqual({
        kind: 'invalid',
        issue: 'ANSWER_SOURCE',
      })
    }
    const answered = {
      question: 'Q',
      status: 'ANSWERED',
      answer: '  A  ',
      answerSourceId: 'source:exact',
    }
    expect(prepareEditorSubmission('clarification', answered, false)).toEqual({
      kind: 'command',
      action: 'SAVE_CLARIFICATION',
      payload: answered,
      continueEmployee: true,
    })
    const open = { question: 'Q', status: 'OPEN' }
    expect(prepareEditorSubmission('clarification', open, false)).toEqual({
      kind: 'command',
      action: 'SAVE_CLARIFICATION',
      payload: open,
      continueEmployee: false,
    })
  })
  it('splits fit-gap evidence identifiers only in a detached payload', () => {
    const form = {
      requirementId: 'req-1',
      evidenceText: ' 9223372036854775800, , evidence/2,9223372036854775800 ',
      status: 'FIT',
      extra: { retained: true },
    }
    const before = structuredClone(form)
    expect(prepareEditorSubmission('fitgap', form, false)).toEqual({
      kind: 'command',
      action: 'SAVE_FIT_GAP',
      payload: {
        requirementId: 'req-1',
        status: 'FIT',
        extra: { retained: true },
        evidenceIds: ['9223372036854775800', 'evidence/2', '9223372036854775800'],
      },
      continueEmployee: false,
    })
    expect(form).toEqual(before)
  })
  it.each([
    [
      'requirement',
      'SAVE_REQUIREMENT',
      { title: 'Exact', statementRevision: 9, graphId: 'graph/1' },
    ],
    [
      'baseline',
      'APPROVE_BASELINE',
      { reason: 'Reviewed', references: [{ requirementId: 'req-1' }] },
    ],
    [
      'solution',
      'SAVE_SOLUTION',
      { title: 'Solution', baselineId: 'baseline-1', sections: [{ text: 'exact' }] },
    ],
    ['release', 'CREATE_RELEASE', { solutionId: '9223372036854775800', purpose: 'candidate' }],
    [
      'review',
      'SAVE_REVIEW',
      { solutionId: 'solution-1', summary: 'checked', issues: [{ severity: 'HIGH' }] },
    ],
    [
      'context',
      'SAVE_CONTEXT',
      { title: 'Draft', text: 'exact', originKind: 'AI_SUGGESTION', sourceRefs: ['source:1'] },
    ],
  ] satisfies [PresalesEditorKind, string, PresalesEditorForm][])(
    'keeps %s action and opaque payload fields',
    (kind, action, payload) => {
      const form: PresalesEditorForm = structuredClone(payload)
      const result = prepareEditorSubmission(kind, form, false)
      expect(result).toEqual({ kind: 'command', action, payload, continueEmployee: false })
      if (result.kind !== 'command') throw new Error('Expected command')
      expect(result.payload).not.toBe(form)
    },
  )
})
