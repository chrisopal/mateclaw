import { describe, it, expect, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useOntologyDraft } from '../useOntologyDraft'
import type { Draft, OntologyDocumentSyntax } from '../../api/types'
import type { OntologyApi } from '../../api/ontologyApi'

const documentView = () => ({
  source: { modelSchema: 'owl-document-v1' as const, syntax: 'FUNCTIONAL' as OntologyDocumentSyntax, documentText: 'Ontology(<https://example.test/plant>)', imports: [], policy: { version: '1', rules: [] } },
  ontologyIri: 'https://example.test/plant', versionIri: null, documentDigest: 'sha256:doc', importLockDigest: 'sha256:imports', axioms: [],
})
const source = (): Draft => ({
  id: '9223372036854775800', ontologyId: '9223372036854775799', baseRevisionId: null, version: 1, draftVersion: 17,
  name: 'Plant', description: '', document: documentView(),
})
function setup() {
  const ws = ref('9223372036854775700')
  const api = { getDraft: vi.fn().mockResolvedValue(source()), saveDraft: vi.fn(), validate: vi.fn(), publish: vi.fn(), operation: vi.fn(), discard: vi.fn(), editDraft: vi.fn() } as unknown as OntologyApi
  const scope = effectScope()
  const draft = scope.run(() => useOntologyDraft(() => source().ontologyId, () => ws.value, api))!
  return { ws, api, scope, draft }
}
describe('ontology document draft', () => {
  it('keeps functional document input and structured conflict after 409', async () => {
    const { draft, api, scope } = setup(); await draft.load(); draft.document.value!.documentText += '\nDeclaration(Class(<https://example.test/Pump>))'
    vi.mocked(api.saveDraft).mockRejectedValue({ status: 409, code: 'DRAFT_CONFLICT' }); await draft.save()
    expect(draft.dirty.value).toBe(true); expect(draft.document.value?.documentText).toContain('Pump'); expect(draft.saveError.value?.code).toBe('DRAFT_CONFLICT'); scope.stop()
  })
  it('expires validation immediately on document edits and keeps string IDs', async () => {
    const { draft, api, scope } = setup(); await draft.load(); vi.mocked(api.validate).mockResolvedValue({ draftVersion: 17, valid: true, violations: [] }); await draft.validate()
    expect(draft.canPublish.value).toBe(true); expect(draft.id.value).toBe('9223372036854775800'); expect(draft.draftVersion.value).toBe(17)
    draft.document.value!.documentText += '\nDeclaration(Class(<https://example.test/Edited>))'; expect(draft.validationReport.value).toBeNull(); expect(draft.canPublish.value).toBe(false); scope.stop()
  })
  it('keeps publishing enabled when validation only reports warnings', async () => {
    const { draft, api, scope } = setup(); await draft.load(); vi.mocked(api.validate).mockResolvedValue({ draftVersion: 17, valid: true, violations: [{ severity: 'WARNING', code: 'OWL_ANNOTATION', path: 'document', message: 'annotation' }] }); await draft.validate()
    expect(draft.validationReport.value?.violations[0]?.severity).toBe('WARNING'); expect(draft.canPublish.value).toBe(true); scope.stop()
  })
  it('cancels and ignores stale results after workspace switch', async () => {
    const { draft, api, ws, scope } = setup(); let resolve!: (value: Draft) => void
    vi.mocked(api.getDraft).mockImplementationOnce(() => new Promise(r => { resolve = r })); const pending = draft.load(); const signal = vi.mocked(api.getDraft).mock.calls[0][2]; ws.value = 'next'; await Promise.resolve(); resolve(source()); await pending
    expect(signal?.aborted).toBe(true); expect(draft.id.value).toBeNull(); scope.stop()
  })
})
describe('publication recovery', () => {
  it('clears terminal publication conflict so refreshed draft uses a fresh operation', async () => {
    const { draft, api, scope } = setup(); await draft.load(); vi.mocked(api.validate).mockResolvedValue({ draftVersion: 17, valid: true, violations: [] }); await draft.validate(); vi.mocked(api.publish).mockRejectedValueOnce({ status: 409, code: 'DRAFT_CONFLICT' }); await draft.publish('first')
    const first = vi.mocked(api.publish).mock.calls[0][2]; expect(draft.publicationPending.value).toBe(false); vi.mocked(api.getDraft).mockResolvedValue({ ...source(), draftVersion: 24 }); await draft.load(); vi.mocked(api.validate).mockResolvedValue({ draftVersion: 24, valid: true, violations: [] }); await draft.validate(); vi.mocked(api.publish).mockRejectedValueOnce({ status: 422, code: 'VALIDATION_FAILED' }); await draft.publish('second')
    const second = vi.mocked(api.publish).mock.calls[1][2]; expect(second.expectedDraftVersion).toBe(24); expect(second.note).toBe('second'); expect(second.operationId).not.toBe(first.operationId); scope.stop()
  })
  it('retains operation through uncertain publication and operation 404 while locking mutations', async () => {
    const { draft, api, scope } = setup(); await draft.load(); vi.mocked(api.validate).mockResolvedValue({ draftVersion: 17, valid: true, violations: [] }); await draft.validate(); vi.mocked(api.publish).mockRejectedValueOnce({ status: 0, code: 'REQUEST_FAILED' }); vi.mocked(api.operation).mockRejectedValueOnce({ status: 404, code: 'NOT_FOUND' }); await draft.publish('original')
    expect(draft.publicationPending.value).toBe(true); expect(await draft.save()).toBe(false); expect(await draft.discard()).toBe(false); expect(await draft.load()).toBe(false); expect(api.saveDraft).not.toHaveBeenCalled()
    const revision = { id: 'r', ontologyId: 'o', version: 2, name: 'Plant', description: '', document: documentView(), availableForNewBindings: true, publishedAt: '2026-09-07T00:00:00Z', publishedBy: '1', publicationNote: 'original', baseRevisionId: null }
    vi.mocked(api.publish).mockResolvedValueOnce(revision); expect(await draft.publish('new note')).toEqual(revision); expect(vi.mocked(api.publish).mock.calls[1][2]).toEqual(vi.mocked(api.publish).mock.calls[0][2]); expect(draft.publicationPending.value).toBe(false); scope.stop()
  })
})
it('does not accept a validation result returned after input changed', async () => {
  const { draft, api, scope } = setup(); await draft.load(); let resolve!: (report: { draftVersion: number; valid: boolean; violations: [] }) => void; vi.mocked(api.validate).mockImplementationOnce(() => new Promise(r => { resolve = r })); const pending = draft.validate(); draft.name.value = 'More recent local input'; resolve({ draftVersion: 17, valid: true, violations: [] }); expect(await pending).toBe(false); expect(draft.validationReport.value).toBeNull(); expect(draft.canPublish.value).toBe(false); scope.stop()
})
it('keeps edits made while save is pending and adopts the returned concurrency counter', async () => {
  const { draft, api, scope } = setup(); await draft.load(); draft.name.value = 'Submitted'; let resolve!: (value: Draft) => void; vi.mocked(api.saveDraft).mockImplementationOnce(() => new Promise(r => { resolve = r })); const pending = draft.save(); draft.name.value = 'More recent local input'; resolve({ ...source(), name: 'Submitted', draftVersion: 44 }); expect(await pending).toBe(true)
  expect(vi.mocked(api.saveDraft).mock.calls[0][2].document.documentText).toContain('plant'); expect(draft.name.value).toBe('More recent local input'); expect(draft.draftVersion.value).toBe(44); expect(draft.dirty.value).toBe(true); scope.stop()
})
