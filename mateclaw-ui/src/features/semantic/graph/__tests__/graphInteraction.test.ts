import { beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import type { GraphResult } from '../../api/workbenchTypes'
import SemanticGraphView from '../SemanticGraphView.vue'

const chart = vi.hoisted(() => ({ setOption: vi.fn(), on: vi.fn(), resize: vi.fn(), dispose: vi.fn() }))
const lifecycle = vi.hoisted(() => ({ init: vi.fn(), resized: null as null | (() => void) }))
vi.mock('echarts/core', () => ({ use: vi.fn(), init: lifecycle.init }))
vi.stubGlobal('ResizeObserver', class {
  constructor(callback: () => void) { lifecycle.resized = callback }
  observe() {}
  disconnect() {}
})
beforeEach(() => { vi.clearAllMocks(); lifecycle.init.mockReturnValue(chart) })

it('offers named entity exploration and revision-specific evidence through real buttons', async () => {
  const host = document.createElement('div')
  document.body.append(host)
  const selectEntity = vi.fn(), selectStatement = vi.fn()
  const app = createApp(SemanticGraphView, {
    definition: { types: [], properties: [], relations: [{ key: 'causedBy', label: '根因', description: '', sourceTypeKey: 'QualityIssue', targetTypeKey: 'RootCause', multiplicity: 'MULTI' }] },
    result: { nodes: [{ id: 'issue', label: '批次裂纹', typeKey: 'QualityIssue', properties: [] }, { id: 'cause', label: '冷却不足', typeKey: 'RootCause', properties: [] }], edges: [{ statementId: 'fact', revision: 3, sourceId: 'issue', targetId: 'cause', predicateKey: 'causedBy' }], traceId: '', truncated: false },
    onSelectEntity: selectEntity,
    onSelectStatement: selectStatement,
  })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  await nextTick()
  try {
    const buttons = [...host.querySelectorAll('button')]
    buttons.find(button => button.textContent?.trim() === '冷却不足')!.click()
    expect(selectEntity).toHaveBeenCalledWith('cause')
    const evidence = buttons.find(button => button.textContent?.includes('查看证据'))!
    expect(evidence.textContent).toContain('批次裂纹 → 根因 → 冷却不足')
    evidence.click()
    expect(selectStatement).toHaveBeenCalledWith({ statementId: 'fact', revision: 3 })
  } finally { app.unmount(); host.remove() }
})

it('defers initialization until drawable, disposes empty graphs, and resizes after tab visibility changes', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const empty: GraphResult = { nodes: [], edges: [], traceId: '', truncated: false }
  const graph = ref<GraphResult>(empty)
  const populated: GraphResult = { ...empty, nodes: [{ id: 'a', typeKey: 'Batch', label: '批次', properties: [] }, { id: 'b', typeKey: 'Equipment', label: '设备', properties: [] }], edges: [{ statementId: 'f', revision: 1, sourceId: 'a', targetId: 'b', predicateKey: 'processedBy' }] }
  const app = createApp({ render: () => h(SemanticGraphView, { result: graph.value, definition: { types: [], properties: [], relations: [] } }) })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  const settle = async () => { await nextTick(); await nextTick() }
  const element = host.querySelector('.semantic-chart')!
  let width = 600, height = 360
  Object.defineProperties(element, { clientWidth: { get: () => width }, clientHeight: { get: () => height } })
  await settle()
  try {
    expect(lifecycle.init).not.toHaveBeenCalled()
    height = 0; graph.value = populated
    await settle()
    expect(lifecycle.init).not.toHaveBeenCalled()
    height = 360; lifecycle.resized!(); await settle()
    expect(lifecycle.init).toHaveBeenCalledTimes(1)
    expect(chart.setOption).toHaveBeenCalledTimes(1)
    width = 0; height = 0; lifecycle.resized!(); await settle()
    expect(chart.resize).not.toHaveBeenCalled()
    width = 320; height = 260; lifecycle.resized!(); await settle()
    expect(chart.resize).toHaveBeenCalledTimes(1)
    expect(chart.setOption).toHaveBeenCalledTimes(2)
    graph.value = empty; height = 0; await settle()
    expect(chart.dispose).toHaveBeenCalledTimes(1)
    expect(lifecycle.init).toHaveBeenCalledTimes(1)
    graph.value = populated; height = 260; await settle()
    expect(lifecycle.init).toHaveBeenCalledTimes(2)
  } finally { app.unmount(); host.remove() }
  expect(chart.dispose).toHaveBeenCalledTimes(2)
})
