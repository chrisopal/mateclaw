import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import ModelSampleReview from '../ModelSampleReview.vue'
import { modelingTaskApi } from '../../api/modelingTaskApi'
import { sourceApi } from '../../api/sourceApi'
import { statementApi } from '../../api/statementApi'
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({ begin: () => ({ id: '1', signal: new AbortController().signal, current: () => true }) }) }))
vi.mock('../../api/modelingTaskApi', () => ({ modelingTaskApi: { list: vi.fn() } }))
vi.mock('../../api/sourceApi', () => ({ sourceApi: { text: vi.fn(), evidence: vi.fn() } }))
vi.mock('../../api/statementApi', () => ({ statementApi: { propose: vi.fn() } }))
let app: App
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks() })
it.each([false, true])('requires explicit identity and unambiguous evidence (repeated=%s)', async repeated => {
 vi.mocked(modelingTaskApi.list).mockResolvedValue([{ id: '10', ontologyId: '2', draftId: '3', goal: '', sources: [], stage: 'DONE', proposals: [{ id: '11', status: 'ACCEPTED', answers: {}, input: { changes: [], questions: [], samples: ['Pump is equipment'], evidence: [] } }] }])
 vi.mocked(sourceApi.text).mockResolvedValue({ id: '5', text: repeated ? 'Pump is equipment. Pump is equipment' : 'Pump is equipment', textDigest: 'hash' })
 vi.mocked(sourceApi.evidence).mockResolvedValue({ id: '6' } as Awaited<ReturnType<typeof sourceApi.evidence>>)
 vi.mocked(statementApi.propose).mockResolvedValue({ id: '7' } as Awaited<ReturnType<typeof statementApi.propose>>)
 const host = document.createElement('div'); document.body.append(host)
 app = createApp(ModelSampleReview, { graphId: '4', ontologyId: '2', entities: [{ id: '8', graphId: '4', iri: 'urn:pump', displayName: 'Pump', assertedTypes: ['urn:Equipment'], status: 'ACTIVE', createdAt: '' }], snapshots: [{ id: '5', sourceTitle: 'Register', captureVersion: 1, sourceKind: 'WIKI_RAW', sourceRef: '9', textDigest: 'hash', createdAt: '' }], document: { source: { syntax: 'FUNCTIONAL', text: '' }, ontologyIri: 'urn:model', versionIri: null, documentDigest: 'h', importLockDigest: 'h', axioms: [{ axiomId: 'a', axiomType: 'Declaration', rendering: 'Declaration(Class(<urn:Equipment>))', signatureIris: ['urn:Equipment'], annotations: [], logical: false }, { axiomId: 'label', axiomType: 'AnnotationAssertion', rendering: 'AnnotationAssertion(rdfs:label <urn:Equipment> "设备")', signatureIris: [], annotations: [], logical: false }] } })
 app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': {} } })).mount(host); await flush()
 expect(statementApi.propose).not.toHaveBeenCalled()
 async function choose(index: number, label: string) {
  ;(host.querySelectorAll('.el-select__wrapper')[index] as HTMLElement).click(); await flush()
  const option = [...document.querySelectorAll('.el-select-dropdown__item')].find(el => el.textContent?.trim() === label) as HTMLElement
  option.click(); await flush()
 }
 await choose(0, '1 · Pump is equipment'); await choose(1, 'Pump · 8'); await choose(3, '设备'); await choose(4, 'Register · 1')
 const submit = [...host.querySelectorAll('button')].find(b => b.textContent?.trim() === 'Submit for review')!
 expect(submit.disabled).toBe(true)
 const textarea = host.querySelector('textarea')!; textarea.value = 'not in source'; textarea.dispatchEvent(new Event('input')); await flush(); expect(submit.disabled).toBe(true)
 textarea.value = 'Pump is equipment'; textarea.dispatchEvent(new Event('input')); await flush(); if (repeated) { expect(submit.disabled).toBe(true); expect(host.textContent).toContain('occurs more than once'); expect(statementApi.propose).not.toHaveBeenCalled(); return }; expect(submit.disabled).toBe(false)
 submit.click(); await flush()
 expect(statementApi.propose).toHaveBeenCalledWith('1', '4', expect.objectContaining({ subjectId: '8', assertionText: 'ClassAssertion(<urn:Equipment> <urn:pump>)', validityKind: 'UNKNOWN', evidenceIds: ['6'] }), expect.any(AbortSignal))
})
