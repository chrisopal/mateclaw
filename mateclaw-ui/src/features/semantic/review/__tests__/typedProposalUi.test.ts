import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import ConflictReviewPanel from '../ConflictReviewPanel.vue'
import { statementApi } from '../../api/statementApi'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import en from '@/i18n/locales/en-US'
vi.mock('../../api/statementApi', () => ({ statementApi: { getChange: vi.fn(), resolve: vi.fn() } }))
let app: App
const flush = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks() })
it('loads typed proposal content before resolution and routes evidence without statement history', async () => {
 const pinia = createPinia(); setActivePinia(pinia); useWorkspaceStore().currentWorkspaceId = 'w'
 const select = vi.fn(), evidence = vi.fn()
 vi.mocked(statementApi.getChange).mockResolvedValue({ id: 'proposal', graphId: 'g', targetStatementId: 's', expectedRevision: 1, status: 'PENDING', resultRevision: null, content: { operationId: 'op', subjectId: 'e', predicateKind: 'PROPERTY', predicateKey: 'power', valueType: 'TEXT', value: '380', unit: 'V', targetEntityId: null, validityKind: 'UNKNOWN', validFrom: null, validTo: null, evidenceIds: ['ev'] } })
 app = createApp({ setup: () => () => h(ConflictReviewPanel, { graphId: 'g', conflicts: [{ id: 'c', kind: 'SINGLE_VALUE_DISAGREEMENT', status: 'OPEN', left: { statementId: 's', revision: 1 }, right: { kind: 'CHANGE_PROPOSAL', statementId: 'proposal', revision: 1 }, resolution: '' }], onSelect: select, onEvidence: evidence }) })
 app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en } }))
 const host = document.createElement('div'); document.body.append(host); app.mount(host); await flush()
 const input = document.querySelector('input')!; input.value = 'reviewed'; input.dispatchEvent(new Event('input')); await flush()
 const resolutionLabels: string[] = [en.semantic.w.keepWinner, en.semantic.w.keepProposal]
 const buttons = () => [...document.querySelectorAll('button')]
 expect(buttons().filter(b => resolutionLabels.includes(b.textContent?.trim() ?? ''))).toHaveLength(2)
 expect(buttons().filter(b => resolutionLabels.includes(b.textContent?.trim() ?? '')).every(b => b.disabled)).toBe(true)
 buttons().find(b => b.textContent?.includes('Change proposal'))!.click(); await flush()
 expect(statementApi.getChange).toHaveBeenCalledWith('w', 'g', 'proposal', expect.any(AbortSignal))
 expect(select).not.toHaveBeenCalled(); expect(document.body.textContent).toContain('380'); expect(document.body.textContent).toContain(en.semantic.w.unknown)
 buttons().find(b => b.textContent?.includes('View evidence'))!.click(); expect(evidence).toHaveBeenCalledWith('ev')
 expect(buttons().filter(b => resolutionLabels.includes(b.textContent?.trim() ?? '')).every(b => !b.disabled)).toBe(true)
 buttons().find(b => b.textContent?.includes('Statement ·'))!.click(); expect(select).toHaveBeenCalledWith('s')
})
