import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import ExtractionWizard from '../ExtractionWizard.vue'
import { extractionApi, type ExtractionTask } from '../../api/extractionApi'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }), onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({ workspace: { currentWorkspaceId: 'ws' } }) }))
vi.mock('../../api/sourceApi', () => ({ sourceApi: { materials: async () => [{ id: 'source', title: 'Report' }] } }))
vi.mock('../../api/extractionApi', () => ({ extractionApi: { capabilities: vi.fn(), list: vi.fn(), start: vi.fn(), read: vi.fn(), suggestions: vi.fn() } }))
const flush = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
let app: App | undefined, host: HTMLElement | undefined
afterEach(() => { app?.unmount(); host?.remove(); sessionStorage.clear(); vi.clearAllMocks() })
async function mount() {
  vi.mocked(extractionApi.list).mockResolvedValue({ items: [] })
  host = document.createElement('div'); document.body.append(host)
  app = createApp(ExtractionWizard, { graphId: 'g', knowledgeBaseId: 'kb', document: undefined, entities: [] })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en } }))
  app.component('ElSelect', defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit, slots }) => () => h('select', { value: props.modelValue, onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLSelectElement).value) }, slots.default?.()) }))
  app.component('ElOption', defineComponent({ props: ['value', 'label'], setup: props => () => h('option', { value: props.value }, props.label) }))
  app.mount(host); await flush()
}
it('retries a lost start response with the same operation and shows zero results separately from failure', async () => {
  vi.mocked(extractionApi.capabilities).mockResolvedValue({ enabled: true, models: [{ id: 'model', name: 'Model' }], ontologyRevisionId: 'revision' })
  const completed = { id: 'task', status: 'SUCCEEDED', sourceTitle: 'Report', sourceText: 'Text', attempts: 1, completedChunks: 1, totalChunks: 1 } as ExtractionTask
  vi.mocked(extractionApi.start).mockRejectedValueOnce(new Error('Network failed')).mockResolvedValue(completed)
  vi.mocked(extractionApi.read).mockResolvedValue(completed)
  vi.mocked(extractionApi.suggestions).mockResolvedValue({ items: [], total: 0 })
  await mount()
  const selects = host!.querySelectorAll('form select')
  for (const [i, value] of ['source', 'model'].entries()) { const select = selects[i] as HTMLSelectElement; select.value = value; select.dispatchEvent(new Event('change')) }
  await flush()
  host!.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(host!.textContent).toContain('Network failed')
  host!.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush(); await flush()
  expect(extractionApi.start).toHaveBeenCalledTimes(2)
  const calls = vi.mocked(extractionApi.start).mock.calls
  expect(calls[0]![2].operationId).toBe(calls[1]![2].operationId)
  expect(host!.textContent).toContain(en.semantic.extraction.empty)
  expect(host!.textContent).not.toContain('Network failed')
})
it('disabled generation preserves history access without offering a start action', async () => {
  vi.mocked(extractionApi.capabilities).mockResolvedValue({ enabled: false, models: [], ontologyRevisionId: 'revision' })
  await mount()
  expect(host!.textContent).toContain(en.semantic.extraction.disabled)
  expect(host!.querySelector('form')).toBeNull()
  expect(extractionApi.list).toHaveBeenCalled()
})
