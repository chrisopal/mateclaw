import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import EvidenceDrawer from '../EvidenceDrawer.vue'
import StatementReviewDrawer from '../StatementReviewDrawer.vue'
import { graphQueryApi } from '../../api/graphQueryApi'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import en from '@/i18n/locales/en-US'
vi.mock('../../api/graphQueryApi', () => ({ graphQueryApi: { evidence: vi.fn(), history: vi.fn() } }))
let app: App
const flush = async () => { await new Promise(r => setTimeout(r, 0)); await nextTick() }
function mount(component: Parameters<typeof createApp>[0]) {
 const pinia = createPinia(); setActivePinia(pinia); useWorkspaceStore().currentWorkspaceId = 'w'
 app = createApp(component); app.use(pinia).use(ElementPlus).use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en } })); const host = document.createElement('div'); document.body.append(host); app.mount(host)
}
// Retain the application, not its mounted component proxy.
afterEach(() => { app?.unmount?.(); document.body.innerHTML = ''; vi.clearAllMocks() })
it('clears old evidence before a new permission failure is returned', async () => {
 const generation = ref(0)
 vi.mocked(graphQueryApi.evidence).mockResolvedValue({ id: 'ev', snapshotId: 'sn', sourceKind: 'WIKI_RAW', sourceRef: 'r', sourceTitle: 'Source', exactQuote: 'SECRET_OLD_QUOTE', startCodePoint: 0, endCodePoint: 16, textDigest: 'd' })
 mount({ setup: () => () => h(EvidenceDrawer, { graphId: 'g', evidenceId: 'ev', generation: generation.value }) })
 await flush(); expect(document.body.textContent).toContain('SECRET_OLD_QUOTE')
 vi.mocked(graphQueryApi.evidence).mockRejectedValue({ status: 403, message: 'Support withdrawn' })
 generation.value++; await flush()
 expect(document.body.textContent).not.toContain('SECRET_OLD_QUOTE')
 expect(document.body.textContent).toContain('Support withdrawn')
})
it('read-only review drawer has no accept or reject actions', async () => {
 mount({ setup: () => () => h(StatementReviewDrawer, { graphId: 'g', canReview: false, statement: { id: 's', graphId: 'g', revision: 1, ontologyRevisionId: 'o', subjectId: 'e', assertion: { kind: 'POSITIVE_DATA_PROPERTY', functionalSyntax: 'DataPropertyAssertion(<https://example.test/p> <https://example.test/e> "v")', signatureIris: ['https://example.test/e', 'https://example.test/p'], subjectIri: 'https://example.test/e', predicateIri: 'https://example.test/p', literal: { lexicalValue: 'v', datatypeIri: 'http://www.w3.org/2001/XMLSchema#string' } }, validityKind: 'UNKNOWN', validFrom: null, validTo: null, evidenceIds: [], reviewStatus: 'PROPOSED', supportStatus: 'SUPPORTED', proposedBy: 'u', createdAt: '' } }) })
 await flush()
 expect([...document.body.querySelectorAll('button')].some(b => ['Accept', 'Reject'].includes(b.textContent?.trim() ?? ''))).toBe(false)
 expect(graphQueryApi.history).not.toHaveBeenCalled()
})
