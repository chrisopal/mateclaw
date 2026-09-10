import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import zh from '@/i18n/locales/zh-CN'
import type { GraphResult } from '../../api/workbenchTypes'
import SemanticGraphView from '../SemanticGraphView.vue'

const chart = vi.hoisted(() => ({ setOption: vi.fn(), on: vi.fn(), resize: vi.fn(), dispose: vi.fn() }))
const lifecycle = vi.hoisted(() => ({ init: vi.fn() }))
vi.mock('echarts/core', () => ({ use: vi.fn(), init: lifecycle.init }))
let width = 800, height = 360
let notifyResize: () => void
const disconnect = vi.fn()
beforeEach(() => {
  vi.clearAllMocks(); lifecycle.init.mockReturnValue(chart); width = 800; height = 360
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => width)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => height)
  vi.stubGlobal('ResizeObserver', class {
    constructor(private callback: () => void) {}
    observe(target: Element) { if (target.classList.contains('semantic-chart')) notifyResize = this.callback }
    disconnect = disconnect
  })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })

it('renders positive IRI relations and exposes entity and assertion controls', async () => {
  const host = document.createElement('div'); document.body.append(host); const selectEntity = vi.fn(), selectStatement = vi.fn()
  const result: GraphResult = { nodes: [{ id: 'issue', iri: 'https://example.test/issue/1', assertedTypes: ['https://example.test/QualityIssue'], label: '批次裂纹', properties: [] }, { id: 'cause', iri: 'https://example.test/cause/1', assertedTypes: ['https://example.test/RootCause'], label: '冷却不足', properties: [] }], edges: [{ statementId: 'fact', revision: 3, sourceId: 'issue', targetId: 'cause', predicateIri: 'https://example.test/causedBy' }], traceId: '', truncated: false }
  const app = createApp(SemanticGraphView, { result, onSelectEntity: selectEntity, onSelectStatement: selectStatement }); app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host); await nextTick()
  try {
    const button = [...host.querySelectorAll('button')].find(item => item.textContent?.includes('冷却不足'))!; button.click(); expect(selectEntity).toHaveBeenCalledWith('cause')
    const edge = [...host.querySelectorAll('button')].find(item => item.textContent?.includes('https://example.test/causedBy'))!; edge.click(); expect(selectStatement).toHaveBeenCalledWith({ statementId: 'fact', revision: 3 })
    expect(chart.setOption).toHaveBeenCalledWith(expect.objectContaining({ series: expect.arrayContaining([expect.objectContaining({ links: [expect.objectContaining({ value: 'https://example.test/causedBy' })] })]) }))
  } finally { app.unmount(); host.remove() }
})

it('keeps the read-only graph empty when there are no asserted relations', async () => {
  const host = document.createElement('div'); document.body.append(host); const result: GraphResult = { nodes: [], edges: [], traceId: '', truncated: false }
  const app = createApp(SemanticGraphView, { result }); app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host); await nextTick()
  expect(host.textContent).toContain('暂时没有已确认的关联关系'); expect(host.querySelector('details')).toBeNull(); expect(lifecycle.init).not.toHaveBeenCalled(); app.unmount(); host.remove()
})

it('defers a hidden graph until it has dimensions and releases its observer on unmount', async () => {
  height = 0
  const host = document.createElement('div'); document.body.append(host)
  const result: GraphResult = { nodes: [
    { id: 'a', iri: 'urn:test:a', assertedTypes: [], label: 'A', properties: [] },
    { id: 'b', iri: 'urn:test:b', assertedTypes: [], label: 'B', properties: [] }
  ], edges: [{ statementId: 'f', revision: 1, sourceId: 'a', targetId: 'b', predicateIri: 'urn:test:relates' }], traceId: '', truncated: false }
  const app = createApp(SemanticGraphView, { result })
  app.use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zh } })).mount(host)
  await nextTick()
  expect(lifecycle.init).not.toHaveBeenCalled()
  height = 360; notifyResize(); await nextTick()
  expect(lifecycle.init).toHaveBeenCalledTimes(1)
  width = 1000; notifyResize(); await nextTick()
  expect(chart.resize).toHaveBeenCalledTimes(1)
  app.unmount()
  expect(disconnect).toHaveBeenCalledTimes(1)
  expect(chart.dispose).toHaveBeenCalledTimes(1)
  notifyResize(); await nextTick()
  expect(lifecycle.init).toHaveBeenCalledTimes(1)
  host.remove()
})
