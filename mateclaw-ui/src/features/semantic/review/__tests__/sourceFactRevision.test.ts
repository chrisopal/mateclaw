import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import SourceFactRevision from '../SourceFactRevision.vue'
import { sourceIncrementalApi, type FactRevisionPreview } from '../../api/sourceIncrementalApi'
import type { DocumentView } from '../../api/types'

vi.mock('../../api/sourceIncrementalApi', () => ({ sourceIncrementalApi: { factPreview: vi.fn(), reviseFact: vi.fn() } }))
const model: DocumentView = {
  source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: '', imports: [], policy: { version: '1', rules: [] } },
  ontologyIri: 'urn:model', versionIri: null, documentDigest: 'model-digest', importLockDigest: '', axioms: [],
}
function preview(text = '设备😀更新：额定电压400.5V。'): FactRevisionPreview {
  return {
    item: { id: 'item', changeId: 'change', graphId: 'graph', sourceKind: 'WIKI_RAW', sourceRef: 'source', itemKind: 'STATEMENT', itemId: 'fact', itemRevision: 2, oldSnapshotId: 'old', newSnapshotId: 'new', oldDigest: 'old-digest', newDigest: 'observed-digest', sourceState: 'ACTIVE', reviewState: 'PENDING', decision: null, reason: null, observedGraphVersion: 8, createdAt: '', reviewedAt: null },
    statement: { id: 'fact', graphId: 'graph', revision: 3, ontologyRevisionId: 'model-version', subjectId: 'entity', assertion: { kind: 'POSITIVE_DATA_PROPERTY', functionalSyntax: '', signatureIris: [], subjectIri: 'urn:device', predicateIri: 'urn:voltage', literal: { lexicalValue: '380', datatypeIri: 'http://www.w3.org/2001/XMLSchema#decimal' } }, validityKind: 'INTERVAL', validFrom: '2026-01-01T00:00:00Z', validTo: '2027-01-01T00:00:00Z', reviewStatus: 'ACCEPTED', supportStatus: 'SUPPORTED', evidenceIds: ['evidence'], proposedBy: 'user', createdAt: '' },
    original: { id: 'old', digest: 'old-digest', text: '额定电压380V。' },
    observed: { id: 'new', digest: 'observed-digest', text },
  }
}
let app: App | undefined, host: HTMLDivElement
const flush = async () => { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); vi.mocked(sourceIncrementalApi.factPreview).mockResolvedValue(preview()) })
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
async function mount() {
  const pinia = createPinia(); setActivePinia(pinia)
  const store = useWorkspaceStore(); store.currentWorkspaceId = 'ws'
  host = document.createElement('div'); document.body.append(host)
  const submitted = vi.fn()
  app = createApp(SourceFactRevision, { graphId: 'graph', itemId: 'item', entities: [], document: model, onSubmitted: submitted })
  app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: {} })).mount(host)
  await flush()
  return { store, submitted }
}
async function input(selector: string, value: string) {
  const field = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!
  field.value = value; field.dispatchEvent(new Event('input', { bubbles: true })); await flush()
}
const submitButton = () => [...host.querySelectorAll('button')].find(button => button.textContent?.includes('Submit revision'))!
const submit = () => host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))

it('preserves decimal datatype and submits exact observed quote, Unicode offsets, digest and current revision', async () => {
  const { submitted } = await mount()
  expect(submitButton().disabled).toBe(true)
  await input('input', '400.5')
  await input('textarea', '额定电压400.5V。')
  expect(submitButton().disabled).toBe(false)
  vi.mocked(sourceIncrementalApi.reviseFact).mockResolvedValue({} as never)
  submit(); await flush()
  expect(sourceIncrementalApi.reviseFact).toHaveBeenCalledWith('ws', 'graph', 'item', {
    expectedObservedDigest: 'observed-digest', expectedRevision: 3,
    assertionText: 'DataPropertyAssertion(<urn:voltage> <urn:device> "400.5"^^<http://www.w3.org/2001/XMLSchema#decimal>)',
    validityKind: 'INTERVAL', validFrom: '2026-01-01T00:00:00Z', validTo: '2027-01-01T00:00:00Z',
    exactQuote: '额定电压400.5V。', startCodePoint: 6, endCodePoint: 17,
  }, expect.any(AbortSignal))
  expect(submitted).toHaveBeenCalledOnce()
})
it('requires a unique new-source quote even when the old source contains a matching excerpt', async () => {
  vi.mocked(sourceIncrementalApi.factPreview).mockResolvedValue(preview('甲：额定电压400V。乙：额定电压400V。'))
  await mount()
  await input('textarea', '额定电压380V。')
  expect(submitButton().disabled).toBe(true)
  await input('textarea', '额定电压400V。')
  expect(host.textContent).toContain('Include context to identify a unique occurrence.')
  expect(submitButton().disabled).toBe(true); submit(); await flush()
  expect(sourceIncrementalApi.reviseFact).not.toHaveBeenCalled()
  await input('textarea', '甲：额定电压400V。')
  expect(submitButton().disabled).toBe(false)
})
it('preserves negative numeric assertions and clears interval fields for unknown validity', async () => {
  const data = preview(); data.statement.assertion.kind = 'NEGATIVE_DATA_PROPERTY'; data.statement.validityKind = 'UNKNOWN'
  vi.mocked(sourceIncrementalApi.factPreview).mockResolvedValue(data)
  await mount(); await input('input', '400.5'); await input('textarea', '额定电压400.5V。')
  vi.mocked(sourceIncrementalApi.reviseFact).mockResolvedValue({} as never)
  submit(); await flush()
  expect(vi.mocked(sourceIncrementalApi.reviseFact).mock.calls[0]![3]).toMatchObject({ assertionText: 'NegativeDataPropertyAssertion(<urn:voltage> <urn:device> "400.5"^^<http://www.w3.org/2001/XMLSchema#decimal>)', validityKind: 'UNKNOWN', validFrom: null, validTo: null })
})
it('discards late preview results from a previous workspace', async () => {
  let finish!: (value: FactRevisionPreview) => void
  vi.mocked(sourceIncrementalApi.factPreview).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { store } = await mount()
  vi.mocked(sourceIncrementalApi.factPreview).mockResolvedValue(preview('CURRENT-WORKSPACE-SOURCE'))
  store.currentWorkspaceId = 'new-ws'; await flush()
  finish(preview('PRIVATE-OLD-WORKSPACE-SOURCE')); await flush()
  expect(host.textContent).toContain('CURRENT-WORKSPACE-SOURCE')
  expect(host.textContent).not.toContain('PRIVATE-OLD-WORKSPACE-SOURCE')
})
it('prevents duplicate submission and ignores completion from the previous workspace', async () => {
  let finish!: (value: never) => void
  const { store, submitted } = await mount()
  await input('textarea', '额定电压400.5V。')
  vi.mocked(sourceIncrementalApi.reviseFact).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  submit(); submit(); await flush()
  expect(sourceIncrementalApi.reviseFact).toHaveBeenCalledOnce()
  store.currentWorkspaceId = 'new-ws'; await flush()
  finish({} as never); await flush()
  expect(submitted).not.toHaveBeenCalled()
})
