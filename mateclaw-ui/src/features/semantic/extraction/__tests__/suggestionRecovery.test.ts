import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import SuggestionReviewPanel from '../SuggestionReviewPanel.vue'
import { extractionApi, type ExtractionSuggestion } from '../../api/extractionApi'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({ workspace: { currentWorkspaceId: 'ws', registerBeforeSwitch: () => () => {} }, begin: () => ({ id: 'ws', current: () => true, signal: new AbortController().signal }) }) }))
vi.mock('../../api/extractionApi', () => ({ extractionApi: { submit: vi.fn(), edit: vi.fn() } }))
let app: App | undefined, host: HTMLElement | undefined
const flush = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks() })
const suggestion: ExtractionSuggestion = { id: 'suggestion', version: 2, status: 'OPEN', subjectIri: 'https://example.test/entity/1', subjectTypeIris: ['https://example.test/Equipment'], subjectName: 'Machine', subjectId: '1', assertionText: 'ClassAssertion(<https://example.test/Equipment> <https://example.test/entity/1>)', targetIri: null, targetTypeIris: [], targetName: null, targetEntityId: null, validityKind: 'UNKNOWN', validFrom: null, validTo: null, quotes: [{ startCodePoint: 0, endCodePoint: 7, exactQuote: 'Machine' }], diagnostics: [], statementId: null, pendingOperationId: null }
async function mount() {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(SuggestionReviewPanel, { graphId: 'g', suggestion, sourceText: 'Machine', entities: [{ id: '1', graphId: 'g', iri: 'https://example.test/entity/1', assertedTypes: ['https://example.test/Equipment'], displayName: 'Machine', status: 'ACTIVE', createdAt: '' }], document: undefined })
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
