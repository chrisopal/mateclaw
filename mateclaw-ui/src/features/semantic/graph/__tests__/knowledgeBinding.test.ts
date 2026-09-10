import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import zh from '@/i18n/locales/zh-CN'
import en from '@/i18n/locales/en-US'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import KnowledgeBindingPanel from '../KnowledgeBindingPanel.vue'
import { graphApi } from '../../api/graphApi'
import { ontologyApi } from '../../api/ontologyApi'

vi.mock('../../api/graphApi', () => ({ graphApi: { binding: vi.fn(), bind: vi.fn() } }))
vi.mock('../../api/ontologyApi', () => ({ ontologyApi: { list: vi.fn(), revisions: vi.fn() } }))

let app: App
let host: HTMLDivElement
const flush = async () => { await new Promise((resolve) => setTimeout(resolve, 0)); await nextTick() }

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(graphApi.binding).mockRejectedValue({ status: 404, code: 'NOT_FOUND' })
  vi.mocked(ontologyApi.list).mockResolvedValue({
    items: [{ id: '11', workspaceId: '1', name: 'Equipment', description: '', latestVersion: 1, latestRevisionId: '12', hasDraft: false, updatedAt: '' }],
    total: 1, page: 1, pageSize: 20,
  })
  vi.mocked(ontologyApi.revisions).mockResolvedValue([{ id: '12', ontologyId: '11', version: 1, name: 'Equipment', description: '', document: { source: { modelSchema: 'owl-document-v1', syntax: 'FUNCTIONAL', documentText: 'Ontology(<https://example.test/equipment>)', imports: [], policy: { version: '1', rules: [] } }, ontologyIri: 'https://example.test/equipment', versionIri: null, documentDigest: 'sha256:doc', importLockDigest: 'sha256:imports', axioms: [] }, availableForNewBindings: true, publishedAt: '', publishedBy: '1', publicationNote: '', baseRevisionId: null }])
})

afterEach(() => { app?.unmount(); host?.remove() })

it('loads published choices beyond the first ontology page', async () => {
  const ontology = { id: '11', workspaceId: '1', name: 'Equipment', description: '', latestVersion: 1, latestRevisionId: '12', hasDraft: false, updatedAt: '' }
  vi.mocked(ontologyApi.list)
    .mockResolvedValueOnce({ items: Array.from({ length: 20 }, (_, i) => ({ ...ontology, id: String(i + 100) })), total: 21, page: 1, pageSize: 20 })
    .mockResolvedValueOnce({ items: [{ ...ontology, id: '999' }], total: 21, page: 2, pageSize: 20 })
  const pinia = createPinia()
  setActivePinia(pinia)
  const workspace = useWorkspaceStore()
  workspace.currentWorkspaceId = '1'
  workspace.accessLoaded = true
  workspace.currentCapabilities = new Set(['publish:ontology'])
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(KnowledgeBindingPanel, { knowledgeBaseId: '42' })
  app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en, 'zh-CN': zh } }))
  app.mount(host)
  await flush()
  expect(ontologyApi.list).toHaveBeenCalledWith('1', '', 2, expect.any(AbortSignal))
  expect(ontologyApi.revisions).toHaveBeenCalledWith('1', '999', expect.any(AbortSignal))
})

it('creates a scoped binding and preserves the knowledge-base id as a string', async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const workspace = useWorkspaceStore()
  workspace.currentWorkspaceId = '1'
  workspace.accessLoaded = true
  workspace.currentCapabilities = new Set(['publish:ontology'])
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(KnowledgeBindingPanel, { knowledgeBaseId: '9223372036854775800' })
  app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en, 'zh-CN': zh } }))
  app.mount(host)
  await flush()
  vi.mocked(graphApi.bind).mockResolvedValue({ graphId: '21', workspaceId: '1', knowledgeBaseId: '9223372036854775800', ontologyRevisionId: '12', ontologyVersion: 1, enabled: true, graphVersion: 0, empty: true, updatedAt: '' })
  ;[...host.querySelectorAll('button')].find((button) => button.textContent?.includes('Create binding'))!.click()
  await flush()
  expect(graphApi.bind).toHaveBeenCalledWith('1', '9223372036854775800', { action: 'ENABLE', revisionId: '12', expectedGraphVersion: undefined }, expect.any(AbortSignal))
  expect(host.textContent).toContain('Pinned to v1')
})
