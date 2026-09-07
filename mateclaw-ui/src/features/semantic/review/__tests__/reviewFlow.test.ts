import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { useStatementReview } from '../useStatementReview'
import { statementApi } from '../../api/statementApi'
import { graphQueryApi } from '../../api/graphQueryApi'
import type { Statement } from '../../api/workbenchTypes'
vi.mock('../../api/statementApi', () => ({ statementApi: { review: vi.fn() } }))
vi.mock('../../api/graphQueryApi', () => ({ graphQueryApi: { history: vi.fn() } }))
let app: App
const statement: Statement = { id: 's', graphId: 'g', revision: 1, ontologyRevisionId: 'o', subjectId: 'e', predicateKind: 'PROPERTY', predicateKey: 'voltage', valueType: 'DECIMAL', value: '380', unit: 'V', targetEntityId: null, validityKind: 'INTERVAL', validFrom: null, validTo: null, evidenceIds: ['ev'], reviewStatus: 'PROPOSED', supportStatus: 'SUPPORTED', proposedBy: 'u', createdAt: '' }
function setup() {
  const pinia = createPinia(); setActivePinia(pinia)
  useWorkspaceStore().currentWorkspaceId = 'w'
  let review!: ReturnType<typeof useStatementReview>
  app = createApp({ setup() { review = useStatementReview(() => 'g'); review.current.value = { ...statement }; return () => h('div') } })
  app.use(pinia).mount(document.createElement('div'))
  return review
}
afterEach(() => { app?.unmount(); vi.clearAllMocks() })
it('409 retains proposed state and reports the actual conflict', async () => {
  const review = setup()
  vi.mocked(statementApi.review).mockRejectedValue({ status: 409, code: 'STATEMENT_VERSION_CONFLICT' })
  expect(await review.confirm()).toBe(false)
  expect(review.current.value?.reviewStatus).toBe('PROPOSED')
  expect(review.error.value?.code).toBe('STATEMENT_VERSION_CONFLICT')
  expect(graphQueryApi.history).not.toHaveBeenCalled()
})
it('success reads the current server revision before updating', async () => {
  const review = setup()
  vi.mocked(statementApi.review).mockResolvedValue(statement)
  vi.mocked(graphQueryApi.history).mockResolvedValue({ statementId: 's', revisions: [statement, { ...statement, revision: 2, reviewStatus: 'ACCEPTED' }] })
  expect(await review.confirm()).toBe(true)
  expect(review.current.value?.revision).toBe(2)
})
it('workspace switch aborts and ignores a late confirmation', async () => {
  const review = setup()
  let resolve!: (s: Statement) => void
  vi.mocked(statementApi.review).mockImplementation(() => new Promise(r => { resolve = r }))
  const pending = review.confirm()
  useWorkspaceStore().currentWorkspaceId = 'other'
  const signal = vi.mocked(statementApi.review).mock.calls[0]![5]!
  expect(signal.aborted).toBe(true)
  vi.mocked(graphQueryApi.history).mockResolvedValue({ statementId: 's', revisions: [{ ...statement, reviewStatus: 'ACCEPTED' }] })
  resolve(statement); expect(await pending).toBe(false)
  expect(review.current.value?.reviewStatus).toBe('PROPOSED')
})
