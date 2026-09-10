import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import GraphMigrationPanel from '../GraphMigrationPanel.vue'
import { migrationApi } from '../../api/migrationApi'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('@/api/index', () => ({ workspaceTeamApi: { getAccess: vi.fn() } }))
vi.mock('../../api/migrationApi', () => ({ migrationApi: { targets: vi.fn(), get: vi.fn() } }))
let app: App
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); vi.resetAllMocks(); setActivePinia(createPinia()) })
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
function mount(props = reactive({ graphId: 'g', revisionId: 'r1', graphVersion: 4, canManage: false })) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(GraphMigrationPanel, props) })
  app.use(createPinia()).use(ElementPlus); useWorkspaceStore().currentWorkspaceId = 'old'
  sessionStorage.setItem('semantic-migration:old:g', 'plan-old'); app.mount(host); return host
}
const plan = { id: 'plan-old', sourceVersion: 1, targetVersion: 2, status: 'PREPARED', planDigest: 'PRIVATE-PLAN', impact: { blockers: [], truncated: false, changedEntities: 0, changedFacts: 0 }, entities: [], facts: [] }
it('clears the old plan immediately on workspace switch', async () => {
  vi.mocked(migrationApi.targets).mockResolvedValue([])
  vi.mocked(migrationApi.get).mockResolvedValue(plan as never)
  const host = mount(); await flush(); expect(host.textContent).toContain('PRIVATE-PLAN')
  useWorkspaceStore().currentWorkspaceId = 'new'; await flush()
  expect(host.textContent).not.toContain('PRIVATE-PLAN')
  expect(migrationApi.get).toHaveBeenCalledTimes(1)
})
it('refreshes migration targets when the bound revision changes', async () => {
  vi.mocked(migrationApi.targets).mockResolvedValue([])
  vi.mocked(migrationApi.get).mockResolvedValue(plan as never)
  const props = reactive({ graphId: 'g', revisionId: 'r1', graphVersion: 4, canManage: false })
  mount(props); await flush()
  expect(migrationApi.targets).toHaveBeenCalledTimes(1)
  props.revisionId = 'r2'; props.graphVersion = 5; await flush()
  expect(migrationApi.targets).toHaveBeenCalledTimes(2)
})
it('ignores late plan responses after a workspace switch', async () => {
  vi.mocked(migrationApi.targets).mockResolvedValue([])
  let resolve!: (value: never) => void
  vi.mocked(migrationApi.get).mockImplementation(() => new Promise(done => { resolve = done }))
  const host = mount(); await flush(); useWorkspaceStore().currentWorkspaceId = 'new'; await flush()
  resolve(plan as never); await flush(); expect(host.textContent).not.toContain('PRIVATE-PLAN')
})
