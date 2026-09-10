import { expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import SourceChangeReviewPanel from '../SourceChangeReviewPanel.vue'

const scope = vi.hoisted(() => ({ currentWorkspaceId: 'ws-1' }))
vi.mock('../../shared/useSemanticScope', () => ({
  useSemanticScope: () => ({
    workspace: scope,
    cancel: vi.fn(),
    begin: () => {
      const id = scope.currentWorkspaceId
      return { id, signal: new AbortController().signal, current: () => id === scope.currentWorkspaceId }
    },
  }),
}))
vi.mock('../../api/sourceChangeApi', () => ({
  sourceChangeApi: {
    pending: vi.fn().mockResolvedValue([{ id: 'item-1', changeId: 'change-1', itemKind: 'STATEMENT', itemId: 'statement-1', itemRevision: 2, newDigest: 'sha256:new', oldDigest: 'sha256:old', reviewState: 'PENDING', sourceState: 'ACTIVE' }]),
    items: vi.fn().mockResolvedValue([]),
    scan: vi.fn().mockResolvedValue({ runId: 'run-1', graphId: 'graph-1', graphMutationVersion: 8, changes: [] }),
    decide: vi.fn().mockResolvedValue({ id: 'item-1', reviewState: 'REVIEWED' }),
  },
}))
import { sourceChangeApi } from '../../api/sourceChangeApi'

async function mountPanel() {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(SourceChangeReviewPanel, { graphId: 'graph-1', graphVersion: 7, canScan: true, canReview: true })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
  return { app, host }
}

it('loads pending reviews and sends the current graph version when scanning', async () => {
  const { app, host } = await mountPanel()
  try {
    expect(sourceChangeApi.pending).toHaveBeenCalledWith('ws-1', 'graph-1', 100, expect.any(AbortSignal))
    const scanButton = [...host.querySelectorAll('button')].find(button => button.textContent?.includes('扫描来源变化')) as HTMLButtonElement
    scanButton.click()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(sourceChangeApi.scan).toHaveBeenCalledWith('ws-1', 'graph-1', expect.objectContaining({ expectedGraphVersion: 7, operationId: expect.any(String) }), expect.any(AbortSignal))
  } finally { app.unmount(); host.remove() }
})

it('states that human decisions do not automatically rewrite knowledge', async () => {
  const { app, host } = await mountPanel()
  try {
    expect(host.textContent).toContain('不会自动改写')
  } finally { app.unmount(); host.remove() }
})
