import { expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus, { ElMessageBox } from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import CandidateEditor from '../CandidateEditor.vue'

const guards = vi.hoisted(() => ({ leave: null as null | (() => Promise<boolean>), workspace: null as null | (() => Promise<boolean>) }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: (guard: () => Promise<boolean>) => { guards.leave = guard }, onBeforeRouteUpdate: vi.fn() }))
vi.mock('../../shared/useSemanticScope', () => ({ useSemanticScope: () => ({ workspace: { registerBeforeSwitch: (guard: () => Promise<boolean>) => { guards.workspace = guard; return vi.fn() } }, begin: vi.fn() }) }))

it('preserves edited candidate values when route or workspace departure is cancelled', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  const app = createApp(CandidateEditor, {
    graphId: 'graph', entities: [{ id: 'batch', graphId: 'graph', typeKey: 'Batch', displayName: '批次 A', status: 'ACTIVE', createdAt: '' }], snapshots: [],
    definition: { types: [], relations: [], properties: [{ key: 'result', label: '结果', description: '', ownerTypeKey: 'Batch', valueType: 'TEXT', multiplicity: 'SINGLE', fixedUnit: null }] },
    target: { id: 'fact', graphId: 'graph', revision: 1, ontologyRevisionId: 'ontology', subjectId: 'batch', predicateKind: 'PROPERTY', predicateKey: 'result', valueType: 'TEXT', value: '合格', unit: null, targetEntityId: null, validityKind: 'UNKNOWN', validFrom: null, validTo: null, evidenceIds: [], reviewStatus: 'ACCEPTED', supportStatus: 'SUPPORTED', proposedBy: 'user', createdAt: '' },
  })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  await nextTick()
  try {
    expect(await guards.leave!()).toBe(true)
    const value = [...host.querySelectorAll('input')].find(input => input.value === '合格')!
    value.value = '发现裂纹'; value.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    expect(await guards.leave!()).toBe(false)
    expect(await guards.workspace!()).toBe(false)
    expect(value.value).toBe('发现裂纹')
    expect(confirm).toHaveBeenCalledTimes(2)
  } finally { app.unmount(); host.remove(); confirm.mockRestore() }
})
