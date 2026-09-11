import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import SuggestionReviewPanel from '../SuggestionReviewPanel.vue'
import { graphApi } from '../../api/graphApi'
import { extractionApi, type ExtractionSuggestion } from '../../api/extractionApi'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({ workspace: { currentWorkspaceId: 'ws', registerBeforeSwitch: () => () => {} }, begin: () => ({ id: 'ws', current: () => true, signal: new AbortController().signal }) }) }))
vi.mock('../../api/extractionApi', () => ({ extractionApi: { submit: vi.fn(), edit: vi.fn() } }))
vi.mock('../../api/graphApi', () => ({ graphApi: { createEntity: vi.fn(), entities: vi.fn() } }))
let app: App | undefined, host: HTMLElement | undefined
const flush = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks(); sessionStorage.clear() })
const suggestion: ExtractionSuggestion = { id: 'suggestion', version: 2, status: 'OPEN', subjectIri: 'https://example.test/entity/1', subjectTypeIris: ['https://example.test/Equipment'], subjectName: 'Machine', subjectId: '1', assertionText: 'ClassAssertion(<https://example.test/Equipment> <https://example.test/entity/1>)', targetIri: null, targetTypeIris: [], targetName: null, targetEntityId: null, validityKind: 'UNKNOWN', validFrom: null, validTo: null, quotes: [{ startCodePoint: 0, endCodePoint: 7, exactQuote: 'Machine' }], diagnostics: [], statementId: null, pendingOperationId: null, subjectResolution: { mode: 'EXISTING', reason: 'Verified serial number' } }
async function mount(value: ExtractionSuggestion = suggestion) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(SuggestionReviewPanel, { graphId: 'g', suggestion: value, sourceText: 'Machine', entities: [{ id: '1', graphId: 'g', iri: 'https://example.test/entity/1', assertedTypes: ['https://example.test/Equipment'], displayName: 'Machine', status: 'ACTIVE', createdAt: '' }, { id: '2', graphId: 'g', iri: 'urn:other:machine', assertedTypes: [], displayName: 'Machine', status: 'ACTIVE', createdAt: '' }], document: undefined })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en } })).mount(host)
  await flush()
}
it('reuses submission identity after refresh when the previous response was lost', async () => {
  vi.mocked(extractionApi.submit).mockRejectedValueOnce(new Error('Response lost')).mockResolvedValue({ statementId: 's', revision: 1 })
  await mount()
  const submit = () => ([...host!.querySelectorAll('button')].find(b => b.textContent?.trim() === en.semantic.extraction.submit))!.click()
  submit(); await flush()
  app!.unmount(); host!.remove()
  await mount(); submit(); await flush()
  expect(extractionApi.submit).toHaveBeenCalledTimes(2)
  const calls = vi.mocked(extractionApi.submit).mock.calls
  expect(calls[0]![3].operationId).toBe(calls[1]![3].operationId)
  expect(calls[1]![3].expectedVersion).toBe(2)
})

const button = (text: string) => [...host!.querySelectorAll('button')].find(item => item.textContent?.trim() === text)!
async function chooseMode(side: string, text: string) {
  const label = [...host!.querySelectorAll(`[data-identity="${side}"] label`)].find(item => item.textContent?.trim() === text) as HTMLElement
  label.click(); await flush()
}
it('never resolves a same-name mention automatically and allows unresolved save only', async () => {
  const raw = { ...suggestion, subjectId: null, subjectIri: 'urn:temporary:machine' }
  vi.mocked(extractionApi.edit).mockResolvedValue(raw)
  await mount(raw)
  expect(button(en.semantic.extraction.submit).disabled).toBe(true)
  button(en.semantic.extraction.submit).click(); await flush()
  expect(extractionApi.submit).not.toHaveBeenCalled()
  host!.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(vi.mocked(extractionApi.edit).mock.calls[0]![3]).toMatchObject({ subjectId: null, subjectIri: raw.subjectIri, assertionText: raw.assertionText })
})
it('offers all same-name entities by ID and saves the explicit selection without changing raw IRI', async () => {
  await mount({ ...suggestion, subjectId: null, subjectIri: 'urn:temporary:machine' })
  await chooseMode('subject', 'Existing entity')
  ;(host!.querySelector('[data-identity="subject"] .el-select') as HTMLElement).click(); await flush()
  const option = [...document.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent?.includes('urn:other:machine')) as HTMLElement
  expect(option).toBeTruthy(); option.click(); await flush()
  const reason = host!.querySelector('[data-identity="subject"] textarea') as HTMLTextAreaElement
  reason.value = 'Verified source serial number'; reason.dispatchEvent(new Event('input')); await flush()
  vi.mocked(extractionApi.edit).mockResolvedValue({ ...suggestion, subjectId: '2' })
  host!.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(vi.mocked(extractionApi.edit).mock.calls[0]![3]).toMatchObject({ subjectId: '2', subjectIri: 'urn:temporary:machine', subjectResolution: { mode: 'EXISTING', reason: 'Verified source serial number' } })
})
it('reads saved target identity and blocks submission after clearing it', async () => {
  await mount({ ...suggestion, targetIri: 'urn:temporary:target', targetName: 'Machine', targetEntityId: '2', targetResolution: { mode: 'EXISTING', reason: 'Verified target serial' } })
  expect(host!.querySelector('[data-identity="target"]')!.textContent).toContain('urn:other:machine')
  expect(button(en.semantic.extraction.submit).disabled).toBe(false)
  await chooseMode('target', 'Unresolved')
  expect(button(en.semantic.extraction.submit).disabled).toBe(true)
})
it('creates only on deliberate click and recovers the generated IRI after a lost response', async () => {
  await mount({ ...suggestion, subjectId: null, subjectIri: 'urn:temporary:machine' })
  await chooseMode('subject', 'New entity')
  expect(graphApi.createEntity).not.toHaveBeenCalled()
  vi.mocked(graphApi.createEntity).mockRejectedValueOnce(new Error('Response lost'))
  button('Create and select').click(); await flush()
  const request = vi.mocked(graphApi.createEntity).mock.calls[0]![2]
  expect(request).toMatchObject({ displayName: 'Machine', assertedTypes: suggestion.subjectTypeIris })
  expect(request.iri).not.toBe('urn:temporary:machine')
  vi.mocked(graphApi.entities).mockResolvedValue({ items: [{ id: 'created', graphId: 'g', iri: request.iri, displayName: 'Machine', assertedTypes: suggestion.subjectTypeIris, status: 'ACTIVE', createdAt: '' }] })
  button('Create and select').click(); await flush()
  expect(graphApi.createEntity).toHaveBeenCalledTimes(1)
  expect(host!.textContent).toContain('Created and selected: Machine')
  const reason = host!.querySelector('[data-identity="subject"] textarea') as HTMLTextAreaElement
  reason.value = 'New machine confirmed'; reason.dispatchEvent(new Event('input')); await flush()
  vi.mocked(extractionApi.edit).mockResolvedValue({ ...suggestion, subjectId: 'created' })
  host!.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(vi.mocked(extractionApi.edit).mock.calls[0]![3]).toMatchObject({ subjectId: 'created', subjectIri: 'urn:temporary:machine', subjectResolution: { mode: 'NEW', reason: 'New machine confirmed' } })
})
it('blocks submission while diagnostics remain', async () => {
  await mount({ ...suggestion, diagnostics: ['Invalid source quote'] })
  expect(button(en.semantic.extraction.submit).disabled).toBe(true)
})

it('requires a recorded reason before submitting a legacy resolved identity', async () => {
 await mount({ ...suggestion, subjectResolution: undefined })
 expect(button(en.semantic.extraction.submit).disabled).toBe(true)
 expect(host!.querySelector('[data-identity="subject"] textarea')).toBeTruthy()
 button(en.semantic.extraction.submit).click(); await flush()
 expect(extractionApi.submit).not.toHaveBeenCalled()
})
