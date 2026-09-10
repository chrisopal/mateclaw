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
  const api = { getDraft: vi.fn().mockResolvedValue(source()), saveDraft: vi.fn(), validate: vi.fn(), publish: vi.fn(), operation: vi.fn(), discard: vi.fn(), editDraft: vi.fn(), retryEdit: vi.fn(), modelEdit: vi.fn() } as unknown as OntologyApi
  const scope = effectScope()
  const draft = scope.run(() => useOntologyDraft(() => source().ontologyId, () => ws.value, api))!
  return { ws, api, scope, draft }
}
const changes = [{ kind: 'ADD' as const, functionalSyntax: 'Declaration(Class(<https://example.test/Pump>))' }]
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

describe('simple edit write boundary', () => {
  it('blocks edits while the document is dirty or the API guard denies editing', async () => {
    const { draft, api, scope } = setup(); await draft.load(); draft.name.value = 'Local change'
    expect(await draft.edit(changes)).toBe(false)
    expect(api.editDraft).not.toHaveBeenCalled()
    scope.stop()

    const denied = setup(); await denied.draft.load()
    const guarded = denied.scope.run(() => useOntologyDraft(() => source().ontologyId, () => denied.ws.value, denied.api, () => false))!
    await guarded.load()
    expect(await guarded.edit(changes)).toBe(false)
    expect(denied.api.editDraft).not.toHaveBeenCalled()
    denied.scope.stop()
  })

  it('blocks edits while publication recovery is pending', async () => {
    const { draft, api, scope } = setup(); await draft.load()
    vi.mocked(api.validate).mockResolvedValue({ draftVersion: 17, valid: true, violations: [] }); await draft.validate()
    vi.mocked(api.publish).mockRejectedValue({ status: 0, code: 'REQUEST_FAILED' }); vi.mocked(api.operation).mockRejectedValue({ status: 404, code: 'NOT_FOUND' }); await draft.publish('publish')
    expect(draft.publicationPending.value).toBe(true)
    expect(await draft.edit(changes)).toBe(false)
    expect(api.editDraft).not.toHaveBeenCalled()
    scope.stop()
  })

  it('ignores a duplicate click while the first edit request is in flight', async () => {
    const { draft, api, scope } = setup(); await draft.load(); let resolve!: (value: Draft) => void
    vi.mocked(api.editDraft).mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const first = draft.edit(changes); const second = draft.edit(changes)
    expect(await second).toBe(false); expect(api.editDraft).toHaveBeenCalledTimes(1); expect(draft.busy.value).toBe(true)
    resolve({ ...source(), draftVersion: 18 }); expect(await first).toBe(true); expect(draft.editPending.value).toBe(false); scope.stop()
  })

  it('retains the same operation body for explicit retry after an ambiguous response', async () => {
    const { draft, api, scope } = setup(); await draft.load()
    vi.mocked(api.editDraft).mockRejectedValueOnce({ status: 0, code: 'REQUEST_FAILED' })
    expect(await draft.edit(changes)).toBe(false); expect(draft.editPending.value).toBe(true)
    const original = vi.mocked(api.editDraft).mock.calls[0][2]
    const result = { ...source(), draftVersion: 18 }
    vi.mocked(api.retryEdit).mockResolvedValueOnce(result)
    expect(await draft.retryEdit()).toBe(true)
    expect(vi.mocked(api.retryEdit).mock.calls[0][2]).toEqual(original)
    expect(draft.editPending.value).toBe(false); expect(draft.draftVersion.value).toBe(18); scope.stop()
  })

  it('blocks every other draft write while an edit operation is unresolved', async () => {
    const { draft, api, scope } = setup(); await draft.load()
    vi.mocked(api.editDraft).mockRejectedValueOnce({ status: 0, code: 'REQUEST_FAILED' }); await draft.edit(changes)
    expect(draft.editPending.value).toBe(true)
    expect(await draft.save()).toBe(false); expect(await draft.validate()).toBe(false); expect(await draft.publish('publish')).toBeNull(); expect(await draft.discard()).toBe(false); expect(await draft.load()).toBe(false)
    expect(api.saveDraft).not.toHaveBeenCalled(); expect(api.validate).not.toHaveBeenCalled(); expect(api.publish).not.toHaveBeenCalled(); expect(api.discard).not.toHaveBeenCalled(); expect(api.getDraft).toHaveBeenCalledTimes(1); scope.stop()
  })

  it('drops an unresolved edit after the request is cancelled by a scope change', async () => {
    const { draft, api, ws, scope } = setup(); await draft.load(); let reject!: (error: unknown) => void
    vi.mocked(api.editDraft).mockImplementationOnce(() => new Promise((_resolve, r) => { reject = r }))
    const pending = draft.edit(changes); const signal = vi.mocked(api.editDraft).mock.calls[0][3]; ws.value = 'next'; await Promise.resolve()
    reject({ status: 0, code: 'REQUEST_FAILED' }); expect(await pending).toBe(false); expect(signal?.aborted).toBe(true); expect(draft.editPending.value).toBe(false); scope.stop()
  })

  it('preserves local form input on a CAS conflict and requires an explicit refresh', async () => {
    const { draft, api, scope } = setup(); await draft.load(); let reject!: (error: unknown) => void
    vi.mocked(api.editDraft).mockImplementationOnce(() => new Promise((_resolve, r) => { reject = r }))
    const pending = draft.edit(changes); draft.name.value = 'Keep this input'; reject({ status: 409, code: 'DRAFT_CONFLICT' })
    expect(await pending).toBe(false); expect(draft.name.value).toBe('Keep this input'); expect(draft.saveError.value?.code).toBe('DRAFT_CONFLICT'); expect(draft.editPending.value).toBe(false); expect(api.getDraft).toHaveBeenCalledTimes(1); scope.stop()
  })
})

it('sends business commands to backend mapping and retains the exact request after lost response',async()=>{
 const {draft,api,scope}=setup();await draft.load()
 const command={kind:'CREATE_TERM' as const,termKind:'OBJECT' as const,name:'设备'}
 vi.mocked(api.modelEdit).mockRejectedValueOnce({status:0,code:'REQUEST_FAILED'})
 expect(await draft.modelEdit([command])).toBe(false);expect(draft.editPending.value).toBe(true)
 expect(api.editDraft).not.toHaveBeenCalled()
 const sent=vi.mocked(api.modelEdit).mock.calls[0]![2];expect(sent.changes).toEqual([command]);expect(JSON.stringify(sent)).not.toContain('functionalSyntax')
 expect(await draft.modelEdit([command])).toBe(false)
 vi.mocked(api.modelEdit).mockResolvedValue({...source(),draftVersion:18})
 expect(await draft.retryEdit()).toBe(true);expect(vi.mocked(api.modelEdit).mock.calls[1]![2]).toEqual(sent)
 draft.name.value='unsaved';expect(await draft.modelEdit([command])).toBe(false);scope.stop()
})
