import { afterEach, beforeEach, it, expect, vi } from 'vitest'
import { createApp, nextTick, type App, type Component } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createI18n } from 'vue-i18n'
import zh from '@/i18n/locales/zh-CN'
import en from '@/i18n/locales/en-US'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import OntologyEditor from '../OntologyEditor.vue'
import OntologyList from '../OntologyList.vue'
import OntologyVersions from '../OntologyVersions.vue'
import { ontologyApi } from '../../api/ontologyApi'
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '9223372036854775800' }, query: {} }),
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
}))
vi.mock('../../api/ontologyApi', () => ({
  ontologyApi: {
    list: vi.fn(),
    get: vi.fn(),
    revisions: vi.fn(),
    getDraft: vi.fn(),
    saveDraft: vi.fn(),
    validate: vi.fn(),
    publish: vi.fn(),
    operation: vi.fn(),
    diff: vi.fn(),
    ensureBuilder: vi.fn(),
  },
}))
const ontologyDocument = () => ({ source: { modelSchema: 'owl-document-v1' as const, syntax: 'FUNCTIONAL' as const, documentText: 'Ontology(<https://example.test/factory>)', imports: [], policy: { version: '1', rules: [] } }, ontologyIri: 'https://example.test/factory', versionIri: null, documentDigest: 'sha256:factory', importLockDigest: 'sha256:imports', axioms: [] })
const data = () => ({
  id: '9223372036854775799',
  ontologyId: '9223372036854775800',
  version: 1,
  draftVersion: 25,
  baseRevisionId: null,
  name: 'Factory',
  description: '',
  document: ontologyDocument(),
})
let app: App, host: HTMLDivElement
const flush = async () => {
  await new Promise((r) => setTimeout(r, 0))
  await nextTick()
}
async function mount(
  caps: ('view:ontology' | 'manage:ontology' | 'publish:ontology')[],
  component: Component = OntologyEditor,
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useWorkspaceStore()
  store.currentWorkspaceId = '1'
  store.accessLoaded = true
  store.currentCapabilities = new Set(caps)
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(component)
  app
    .use(pinia)
    .use(ElementPlus)
    .use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en, 'zh-CN': zh } }))
  app.mount(host)
  await flush()
  return store
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(ontologyApi.getDraft).mockResolvedValue(data())
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  document.body.innerHTML = ''
})
it('renders viewer read-only and preserves large ID in real request', async () => {
  await mount(['view:ontology'])
  expect((host.querySelector('#ontology-name') as HTMLInputElement).disabled).toBe(true)
  expect(host.textContent).toContain('viewing only')
  expect(host.textContent).not.toContain('Save draft')
  expect(ontologyApi.getDraft).toHaveBeenCalledWith('1', '9223372036854775800', expect.any(AbortSignal))
})
it('edits real draft state through an Element Plus input and keeps it after a 409', async () => {
  await mount(['view:ontology', 'manage:ontology'])
  const input = host.querySelector('#ontology-name') as HTMLInputElement
  input.value = 'Updated factory'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  vi.mocked(ontologyApi.saveDraft).mockRejectedValue({ status: 409, code: 'DRAFT_CONFLICT' })
  const save = [...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Save draft'))!
  save.click()
  await flush()
  expect(input.value).toBe('Updated factory')
  expect(host.textContent).toContain('Another editor changed')
  expect(host.textContent).toContain('Unsaved changes')
  expect(vi.mocked(ontologyApi.saveDraft).mock.calls[0][2].expectedDraftVersion).toBe(25)
})
it('locks editor and exposes recovery after uncertain publication', async () => {
  await mount(['view:ontology', 'manage:ontology', 'publish:ontology'])
  vi.mocked(ontologyApi.validate).mockResolvedValue({ draftVersion: 25, valid: true, violations: [] })
  ;[...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Validate draft'))!.click()
  await flush()
  vi.mocked(ontologyApi.diff).mockResolvedValue({
    fromRevisionId: null,
    toRevisionId: data().id,
    changes: [],
  })
  ;[...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Publish version'))!.click()
  await flush()
  const note = document.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  note.value = 'First release'
  note.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  vi.mocked(ontologyApi.publish).mockRejectedValue({ status: 0, code: 'REQUEST_FAILED' })
  vi.mocked(ontologyApi.operation).mockRejectedValue({ status: 404, code: 'NOT_FOUND' })
  ;[...document.querySelectorAll('button')]
    .find((b) => b.textContent?.includes('Confirm publication'))!
    .click()
  await flush()
  expect((host.querySelector('#ontology-name') as HTMLInputElement).disabled).toBe(true)
  expect(host.textContent).toContain('Recover publication')
  expect(host.textContent).not.toContain('Save draft')
})
it('renders the real list and submits a scoped search with page reset', async () => {
  vi.mocked(ontologyApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  await mount(['view:ontology'], OntologyList)
  expect(host.textContent).toContain('No ontologies yet')
  expect(host.textContent).not.toContain('New ontology')
  const input = host.querySelector('input')!
  input.value = 'Plant'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
  await flush()
  expect(ontologyApi.list).toHaveBeenLastCalledWith('1', 'Plant', 1, expect.any(AbortSignal))
})
it('launches the ontology builder from the list for members', async () => {
  vi.mocked(ontologyApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  vi.mocked(ontologyApi.ensureBuilder).mockResolvedValue({ agentId: '9223372036854775807' })
  await mount(['view:ontology', 'manage:ontology'], OntologyList)
  const launch = [...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Generate from source'))!
  launch.click()
  await flush()
  expect(ontologyApi.ensureBuilder).toHaveBeenCalledWith('1', expect.any(AbortSignal))
})
it('clears an in-flight builder launch when the workspace changes', async () => {
  vi.mocked(ontologyApi.list).mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
  let resolveEnsure!: (value: { agentId: string }) => void
  vi.mocked(ontologyApi.ensureBuilder).mockImplementation(() => new Promise((resolve) => { resolveEnsure = resolve }))
  const store = await mount(['view:ontology', 'manage:ontology'], OntologyList)
  const launch = [...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Generate from source'))!
  launch.click()
  await nextTick()
  expect(launch.className).toContain('is-loading')
  store.currentWorkspaceId = '2'
  await nextTick()
  expect(launch.className).not.toContain('is-loading')
  resolveEnsure({ agentId: '9223372036854775807' })
  await flush()
})
it('renders immutable version detail and compares saved revisions for viewers', async () => {
  const revision = {
    ...data(),
    availableForNewBindings: true,
    publishedAt: '2026-09-07T00:00:00Z',
    publishedBy: '1',
    publicationNote: 'Initial release',
  }
  vi.mocked(ontologyApi.get).mockResolvedValue({
    id: data().ontologyId,
    workspaceId: '1',
    name: 'Factory',
    description: '',
    latestVersion: 1,
    latestRevisionId: revision.id,
    hasDraft: false,
    updatedAt: revision.publishedAt,
  })
  vi.mocked(ontologyApi.revisions).mockResolvedValue([revision])
  vi.mocked(ontologyApi.diff).mockResolvedValue({
    fromRevisionId: null,
    toRevisionId: revision.id,
    changes: [{ kind: 'ADDED', category: 'axiom', key: 'axiom-1', before: null, after: revision.document.axioms[0] ?? null }],
  })
  await mount(['view:ontology'], OntologyVersions)
  expect(host.textContent).toContain('Initial release')
  expect(host.textContent).not.toContain('Create draft from selection')
  expect(host.textContent).not.toContain('Disable new bindings')
  ;[...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Compare versions'))!.click()
  await flush()
  expect(ontologyApi.diff).toHaveBeenCalledWith(
    '1',
    data().ontologyId,
    null,
    revision.id,
    expect.any(AbortSignal),
  )
  expect(host.textContent).toContain('ADDED')
})
it('registers the real dirty editor guard and preserves input on rejected workspace switch', async () => {
  const store = await mount(['view:ontology', 'manage:ontology'])
  localStorage.setItem('mc-workspace-id', '1')
  const input = host.querySelector('#ontology-name') as HTMLInputElement
  input.value = 'Keep my draft'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
  await store.switchWorkspace('2')
  expect(confirm).toHaveBeenCalled()
  expect(store.currentWorkspaceId).toBe('1')
  expect(localStorage.getItem('mc-workspace-id')).toBe('1')
  expect(store.can('manage:ontology')).toBe(true)
  expect(input.value).toBe('Keep my draft')
  confirm.mockRestore()
})
