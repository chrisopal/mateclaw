import { beforeEach, it, expect, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { workspaceTeamApi } from '@/api'
vi.mock('@/api/index', () => ({
  workspaceTeamApi: { getAccess: vi.fn().mockResolvedValue({ data: { capabilities: ['view:ontology'] } }) },
}))
beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('mc-workspace-id', 'old')
  setActivePinia(createPinia())
  vi.clearAllMocks()
})
it('refusal preserves workspace, persisted ID and capabilities; disposer restores switching', async () => {
  const store = useWorkspaceStore()
  store.accessLoaded = true
  store.currentCapabilities = new Set(['chat'])
  const dispose = store.registerBeforeSwitch(async () => false)
  await store.switchWorkspace('new')
  expect(store.currentWorkspaceId).toBe('old')
  expect(localStorage.getItem('mc-workspace-id')).toBe('old')
  expect(store.can('chat')).toBe(true)
  expect(workspaceTeamApi.getAccess).not.toHaveBeenCalled()
  dispose()
  await store.switchWorkspace('new')
  expect(store.currentWorkspaceId).toBe('new')
})
it('older access response cannot overwrite newer workspace capabilities', async () => {
  const store = useWorkspaceStore()
  let resolve!: (v: unknown) => void
  vi.mocked(workspaceTeamApi.getAccess).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }) as never,
  )
  const first = store.switchWorkspace('one')
  await Promise.resolve()
  await store.switchWorkspace('two')
  resolve({ data: { capabilities: ['manage:ontology'] } })
  await first
  expect(store.currentWorkspaceId).toBe('two')
  expect(store.can('view:ontology')).toBe(true)
  expect(store.can('manage:ontology')).toBe(false)
})
