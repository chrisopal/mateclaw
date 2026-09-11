import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import type { Core } from 'cytoscape'
import Canvas from '../components/OntologyGraphCanvas.vue'
const state = vi.hoisted(() => ({
  core: null as Core | null,
  resize: undefined as ResizeObserverCallback | undefined,
}))
vi.mock('cytoscape', async (original) => {
  const actual = await original<{ default: typeof import('cytoscape') }>()
  const create = Object.assign(
    (options: Record<string, unknown>) => {
      const core = actual.default({
        ...options,
        container: undefined,
        headless: true,
      })
      vi.spyOn(core, 'fit')
      vi.spyOn(core, 'resize')
      state.core = core
      return core
    },
    { use: actual.default.use },
  )
  return { ...actual, default: create }
})
let app: App,
  host: HTMLDivElement,
  width = 900
const flush = async () => {
  await nextTick()
  await new Promise((r) => setTimeout(r, 0))
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  state.core = null
})
it('fits six nodes initially and on a large/narrow resize while preserving user pan for small changes', async () => {
  vi.stubGlobal('innerWidth', 1440)
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: ResizeObserverCallback) {
        state.resize = callback
      }
      observe() {}
      disconnect() {}
    },
  )
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
    () => ({
      width,
      height: 480,
      x: 0,
      y: 0,
      left: 0,
      right: width,
      top: 0,
      bottom: 480,
      toJSON() {
        return {}
      },
    }),
  )
  const nodes = Array.from({ length: 6 }, (_, i) => ({
    id: `node-${i}`,
    iri: `urn:${i}`,
    kind: 'class' as const,
    label: `设备${i}`,
    axiomIds: [],
  }))
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    render: () =>
      h(Canvas, {
        nodes,
        edges: [],
        selectedNodeId: '',
        selectedEdgeId: '',
        highlightedIds: [],
      }),
  })
  app
    .use(ElementPlus)
    .use(createI18n({ legacy: false, locale: 'zh-CN', messages: {} }))
    .mount(host)
  await flush()
  const cy = state.core!,
    surface = host.querySelector('.graph-surface')!
  const resize = () =>
    state.resize!(
      [
        {
          target: surface,
          contentRect: { width, height: 340 },
        } as ResizeObserverEntry,
      ],
      {} as ResizeObserver,
    )
  expect(cy.nodes()).toHaveLength(6)
  expect(cy.fit).toHaveBeenCalled()
  vi.mocked(cy.fit).mockClear()
  cy.pan({ x: 42, y: 18 })
  const pan = cy.pan()
  width = 890
  resize()
  expect(cy.resize).toHaveBeenCalled()
  expect(cy.fit).not.toHaveBeenCalled()
  expect(cy.pan()).toEqual(pan)
  width = 290
  vi.stubGlobal('innerWidth', 390)
  resize()
  expect(cy.fit).toHaveBeenCalledOnce()
  expect(cy.nodes()).toHaveLength(6)
  vi.mocked(cy.fit).mockClear()
  width = 285
  resize()
  expect(cy.fit).not.toHaveBeenCalled()
  const zoom = host.querySelector('.graph-zoom-controls')!
  expect(zoom.querySelectorAll('button')).toHaveLength(2)
  expect(zoom.querySelector('output')).not.toBeNull()
  expect(
    host.querySelector<HTMLDetailsElement>('.graph-layout-controls')?.open,
  ).toBe(false)
})
