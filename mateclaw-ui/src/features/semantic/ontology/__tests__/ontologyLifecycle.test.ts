import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import ElementPlus from 'element-plus'
import OntologyLifecycleAction from '../components/OntologyLifecycleAction.vue'
import { semanticRequest } from '../../api/ontologyApi'
import { useSemanticScope } from '../../shared/useSemanticScope'
vi.mock('vue-i18n', () => ({ useI18n: () => ({ locale: { value: 'en-US' } }) }))
vi.mock('../../api/ontologyApi', () => ({ semanticRequest: vi.fn() }))
vi.mock('../../shared/useSemanticScope', async () => { const { reactive } = await import('vue'); const workspace = reactive({ currentWorkspaceId: 'ws', can: vi.fn(() => true) }); return { useSemanticScope: () => { let generation = 0; return { workspace, cancel: () => { generation++ }, begin: () => { const version = ++generation, id = workspace.currentWorkspaceId; return { id, signal: new AbortController().signal, current: () => version === generation && id === workspace.currentWorkspaceId } } } } } })
let app: App, host: HTMLElement
const flush = async () => { await nextTick(); await new Promise(r => setTimeout(r, 0)); await nextTick() }
beforeEach(() => { vi.clearAllMocks(); const { workspace } = useSemanticScope(); workspace.currentWorkspaceId = 'ws'; vi.mocked(workspace.can).mockReturnValue(true) })
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
async function mount(archived = false) { host = document.createElement('div'); document.body.append(host); const updated = vi.fn(); app = createApp(OntologyLifecycleAction, { ontology: { id: 'model', name: 'Equipment', description: '', latestVersion: 1, latestRevisionId: 'r1', hasDraft: false, workspaceId: 'ws', updatedAt: '2026-09-12T00:00:00Z', archived }, onUpdated: updated }); app.use(ElementPlus).mount(host); await flush(); return updated }
async function open() { host.querySelector('button')!.click(); await flush(); return document.body.querySelector<HTMLButtonElement>('.el-dialog__footer .el-button--primary')! }
it.each([false, true])('uses the correct lifecycle endpoint with current updatedAt (archived=%s)', async archived => { const updated = await mount(archived); let finish!: (v: never) => void; vi.mocked(semanticRequest).mockImplementation(() => new Promise(r => { finish = r })); const confirm = await open(); confirm.click(); confirm.click(); await flush(); expect(semanticRequest).toHaveBeenCalledTimes(1); expect(semanticRequest).toHaveBeenCalledWith('ws', { url: `/semantic/ontologies/model/${archived ? 'restore' : 'archive'}`, method: 'POST', data: { expectedUpdatedAt: '2026-09-12T00:00:00Z' } }, expect.any(AbortSignal)); finish({ id: 'model', archived: !archived } as never); await flush(); expect(updated).toHaveBeenCalledOnce() })
it('hides lifecycle action without publish permission', async () => { vi.mocked(useSemanticScope().workspace.can).mockReturnValue(false); await mount(); expect(host.querySelector('button')).toBeNull(); expect(semanticRequest).not.toHaveBeenCalled() })
it('suppresses a late lifecycle response after workspace change', async () => { const updated = await mount(); let finish!: (v: never) => void; vi.mocked(semanticRequest).mockImplementation(() => new Promise(r => { finish = r })); (await open()).click(); await flush(); useSemanticScope().workspace.currentWorkspaceId = 'other'; await flush(); finish({ id: 'private-old-model' } as never); await flush(); expect(updated).not.toHaveBeenCalled() })

it('suppresses a late response when the ontology changes within the same workspace', async () => {
  const ontology = reactive({ id: 'model', name: 'Equipment', description: '', latestVersion: 1, latestRevisionId: 'r1', hasDraft: false, workspaceId: 'ws', updatedAt: '2026-09-12T00:00:00Z', archived: false })
  host = document.createElement('div'); document.body.append(host)
  const updated = vi.fn()
  app = createApp(OntologyLifecycleAction, { ontology, onUpdated: updated }); app.use(ElementPlus).mount(host); await flush()
  let finish!: (value: never) => void
  vi.mocked(semanticRequest).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  ;(await open()).click(); await flush()
  ontology.id = 'new-model'; ontology.name = 'New model'; await flush()
  finish({ id: 'model', archived: true } as never); await flush()
  expect(updated).not.toHaveBeenCalled()
})
