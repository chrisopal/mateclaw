import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import ElementPlus from 'element-plus'
import OntologyApplicationsPanel from '../components/OntologyApplicationsPanel.vue'
import { ontologyApi } from '../../api/ontologyApi'
import { graphApi } from '../../api/graphApi'
import { useSemanticScope } from '../../shared/useSemanticScope'
import { useRouter } from 'vue-router'
vi.mock('vue-i18n', () => ({ useI18n: () => ({ locale: { value: 'en-US' } }) }))
vi.mock('vue-router', () => { const push = vi.fn(); return { useRouter: () => ({ push }) } })
vi.mock('../../api/ontologyApi', () => ({ ontologyApi: { usage: vi.fn() } }))
vi.mock('../../api/graphApi', () => ({ graphApi: { binding: vi.fn(), bind: vi.fn() } }))
vi.mock('../../shared/useSemanticScope', async () => { const { reactive } = await import('vue'); const workspace = reactive({ currentWorkspaceId: 'ws', can: vi.fn(() => true) }); return { useSemanticScope: () => ({ workspace, begin: () => { const id = workspace.currentWorkspaceId; return { id, signal: new AbortController().signal, current: () => id === workspace.currentWorkspaceId } } }) } })
const rows = [{ kbId: 'kb1', kbName: 'Production', graphId: 'g1', ontologyRevisionId: 'r1', ontologyVersion: 1, enabled: true }, { kbId: 'kb2', kbName: 'Maintenance', graphId: 'g2', ontologyRevisionId: 'r2', ontologyVersion: 2, enabled: false }]
let app: App, host: HTMLElement
const flush = async () => { await nextTick(); await new Promise(r => setTimeout(r, 0)); await nextTick() }
beforeEach(() => { vi.resetAllMocks(); const { workspace } = useSemanticScope(); workspace.currentWorkspaceId = 'ws'; vi.mocked(workspace.can).mockReturnValue(true); vi.mocked(ontologyApi.usage).mockResolvedValue({ items: rows, total: 2 } as never) })
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(OntologyApplicationsPanel, { ontologyId: 'model', target: { id: 'r3', availableForNewBindings: true } }); app.use(ElementPlus).mount(host); await flush() }
const buttons = (text: string) => [...host.querySelectorAll('button')].filter(b => b.textContent?.trim() === text)
it('shows independent versions and routes upgrades to the selected target', async () => { await mount(); expect(host.textContent).toContain('v1'); expect(host.textContent).toContain('v2'); expect(buttons('View knowledge')).toHaveLength(2); buttons('Prepare upgrade')[0]!.click(); expect(useRouter().push).toHaveBeenCalledWith({ path: '/semantic/graphs/g1', query: { tab: 'migration', targetRevision: 'r3' } }); expect(buttons('Prepare upgrade')[1]!.disabled).toBe(true) })
it.each([true, false])('uses fresh binding CAS to pause or resume (enabled=%s)', async enabled => { await mount(); const row = rows[enabled ? 0 : 1]!; vi.mocked(graphApi.binding).mockResolvedValue({ graphId: row.graphId, enabled, ontologyRevisionId: row.ontologyRevisionId, graphVersion: 19 } as never); vi.mocked(graphApi.bind).mockResolvedValue({ enabled: !enabled, graphVersion: 20 } as never); buttons(enabled ? 'Pause' : 'Resume')[0]!.click(); await flush(); expect(graphApi.bind).toHaveBeenCalledWith('ws', row.kbId, { action: enabled ? 'DISABLE' : 'ENABLE', revisionId: row.ontologyRevisionId, expectedGraphVersion: 19 }, expect.any(AbortSignal)) })
it('blocks a changed binding instead of mutating the replacement application', async () => { await mount(); vi.mocked(graphApi.binding).mockResolvedValue({ graphId: 'replacement', enabled: true, ontologyRevisionId: 'r1', graphVersion: 20 } as never); buttons('Pause')[0]!.click(); await flush(); expect(graphApi.bind).not.toHaveBeenCalled(); expect(host.textContent).toContain('application changed') })
it('keeps knowledge history accessible without modification permission', async () => { vi.mocked(useSemanticScope().workspace.can).mockReturnValue(false); await mount(); expect(buttons('Pause')).toHaveLength(0); expect(buttons('Resume')).toHaveLength(0); expect(buttons('Prepare upgrade')).toHaveLength(0); expect(buttons('View knowledge')).toHaveLength(2) })
it('discards late applications after workspace switch', async () => { let finish!: (v: never) => void; vi.mocked(ontologyApi.usage).mockImplementationOnce(() => new Promise(r => { finish = r })); await mount(); vi.mocked(ontologyApi.usage).mockResolvedValue({ items: [], total: 0 } as never); useSemanticScope().workspace.currentWorkspaceId = 'other'; await flush(); finish({ items: rows, total: 2 } as never); await flush(); expect(host.textContent).not.toContain('Production') })
it('does not change the old workspace binding after a late preflight response', async () => { await mount(); let finish!: (v: never) => void; vi.mocked(graphApi.binding).mockImplementationOnce(() => new Promise(r => { finish = r })); buttons('Pause')[0]!.click(); useSemanticScope().workspace.currentWorkspaceId = 'other'; await flush(); finish({ graphId: 'g1', enabled: true, ontologyRevisionId: 'r1', graphVersion: 19 } as never); await flush(); expect(graphApi.bind).not.toHaveBeenCalled() })
