import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import GraphMigrationPanel from '../GraphMigrationPanel.vue'
import { migrationApi } from '../../api/migrationApi'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, locale: { value: 'en-US' } }) }))
vi.mock('@/api/index', () => ({ workspaceTeamApi: { getAccess: vi.fn() } }))
vi.mock('../../api/migrationApi', () => ({ migrationApi: { targets: vi.fn(), get: vi.fn(), act: vi.fn(), prepare: vi.fn() } }))
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

const executed = { ...plan, status: 'EXECUTED', sourceRevisionId: 'r1', targetRevisionId: 'r2', executedGraphVersion: 5 }
it('enables rollback only while graph version and bound target revision match the executed plan', async () => {
  vi.mocked(migrationApi.targets).mockResolvedValue([])
  vi.mocked(migrationApi.get).mockResolvedValue(executed as never)
  vi.mocked(migrationApi.act).mockResolvedValue({ ...executed, status: 'ROLLED_BACK' } as never)
  const props = reactive({ graphId: 'g', revisionId: 'r2', graphVersion: 5, canManage: true })
  const host = mount(props); await flush()
  const rollback = () => [...host.querySelectorAll('button')].find(b => b.textContent?.includes('semantic.migration.rollback'))!
  expect(rollback().disabled).toBe(false)
  props.graphVersion = 6; await nextTick()
  expect(rollback().disabled).toBe(true)
  expect(host.textContent).toContain('knowledge base changed after migration')
  rollback().click(); expect(migrationApi.act).not.toHaveBeenCalled()
  props.graphVersion = 5; props.revisionId = 'r3'; await flush()
  expect(rollback().disabled).toBe(true)
  expect(host.textContent).toContain('applied version has changed')
  props.revisionId = 'r2'; await flush()
  expect(rollback().disabled).toBe(false); rollback().click(); await flush()
  expect(migrationApi.act).toHaveBeenCalledWith('old', 'g', 'plan-old', 'rollback', expect.objectContaining({ expectedPlanDigest: 'PRIVATE-PLAN', expectedGraphVersion: 5, operationId: expect.any(String) }), expect.any(AbortSignal))
})
it('shows business stages and collapses technical details while preserving accessible mapping inputs', async () => {
  vi.mocked(migrationApi.targets).mockResolvedValue([{ id: 'r2', name: 'Equipment', version: 2 }])
  vi.mocked(migrationApi.get).mockResolvedValue({ ...plan, sourceRevisionId: 'r1', targetRevisionId: 'r2' } as never)
  const host = mount(reactive({ graphId: 'g', revisionId: 'r1', graphVersion: 4, canManage: true })); await flush()
  expect(host.textContent).toContain('Prepare and check impact')
  expect(host.textContent).toContain('Checked, awaiting approval')
  expect(host.textContent).toContain('label rename with the same identifier needs no mapping')
  expect(host.querySelector('[data-testid="migration-current-version"]')?.textContent).toContain('v1')
  const details = [...host.querySelectorAll('details')].find(d => d.textContent?.includes('PRIVATE-PLAN'))!
  expect(details.open).toBe(false)
  ;[...host.querySelectorAll('button')].find(b => b.textContent?.includes('semantic.migration.addMapping'))!.click(); await nextTick()
  expect(host.querySelector('[aria-label="Old identifier 1"]')).toBeTruthy()
  expect(host.querySelector('[aria-label="New identifier 1"]')).toBeTruthy()
  expect(host.querySelector('[aria-label="Remove mapping 1"]')).toBeTruthy()
})
