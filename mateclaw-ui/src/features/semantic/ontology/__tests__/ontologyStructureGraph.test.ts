import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import type { Definition } from '../../api/types'
import OntologyStructureGraph from '../components/OntologyStructureGraph.vue'
import { buildOntologyGraph } from '../ontologyGraphModel'
const type = (key: string) => ({ key, label: key, description: '' })
const definition = (): Definition => ({ types: [type('A'), type('B'), type('Isolated')], properties: [
  { ...type('Weight'), ownerTypeKey: 'A', valueType: 'DECIMAL', multiplicity: 'SINGLE', fixedUnit: 'kg', constraints: { minimum: '0', maximum: '100' }, aliases: ['Mass'], deprecated: true },
], relations: [
  { ...type('owns'), sourceTypeKey: 'A', targetTypeKey: 'B', multiplicity: 'MULTI' },
  { ...type('uses'), sourceTypeKey: 'A', targetTypeKey: 'B', multiplicity: 'MULTI' },
  { ...type('reverse'), sourceTypeKey: 'B', targetTypeKey: 'A', multiplicity: 'MULTI' },
  { ...type('self'), sourceTypeKey: 'A', targetTypeKey: 'A', multiplicity: 'MULTI' },
  { ...type('self2'), sourceTypeKey: 'A', targetTypeKey: 'A', multiplicity: 'MULTI' },
] })
it('retains isolated types, correct properties, and distinct parallel/reverse/self edges', () => {
  const graph = buildOntologyGraph(definition())
  expect(graph.nodes).toHaveLength(3)
  expect(graph.nodes[0]!.properties[0]!.key).toBe('Weight')
  expect(graph.nodes[1]!.properties).toEqual([])
  expect(graph.edges).toHaveLength(5)
  expect(new Set(graph.edges.map(edge => edge.path)).size).toBe(5)
  expect(graph.edges.every(edge => !edge.path.includes('NaN'))).toBe(true)
})
it('reports unknown endpoint and owner references without discarding valid structure', () => {
  const data = definition()
  data.relations[0]!.targetTypeKey = 'Missing'
  data.properties[0]!.ownerTypeKey = 'Missing'
  const graph = buildOntologyGraph(data)
  expect(graph.nodes).toHaveLength(3)
  expect(graph.edges).toHaveLength(4)
  expect(graph.invalidRelations[0]!.key).toBe('owns')
  expect(graph.orphanProperties[0]!.key).toBe('Weight')
})
let app: App | undefined
let host: HTMLElement | undefined
afterEach(() => { app?.unmount(); host?.remove(); vi.unstubAllGlobals() })
it('selects complete property details and clears selection when a revision replaces the definition', async () => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  const data = ref(definition())
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(OntologyStructureGraph, { definition: data.value }) })
  app.use(createI18n({ legacy: false, locale: 'en', missingWarn: false, fallbackWarn: false, messages: {} }))
  app.mount(host)
  expect(host.querySelector('aside')).toBeNull()
  const selector = host.querySelector('select')!
  selector.value = 'type-0'; selector.dispatchEvent(new Event('change')); await nextTick()
  const details = host.querySelector('aside')!
  for (const value of ['Weight', 'kg', 'Mass', '100', 'semantic.deprecated']) expect(details.textContent).toContain(value)
  data.value = { ...definition(), properties: [] }; await nextTick(); await nextTick()
  expect(selector.value).toBe('')
  expect(host.querySelector('aside')).toBeNull()
})
it('keeps all 100 types and 500 relations in a large definition', () => {
  const data: Definition = { types: Array.from({ length: 100 }, (_, index) => type(`T${index}`)), properties: [], relations: Array.from({ length: 500 }, (_, index) => ({ ...type(`R${index}`), sourceTypeKey: `T${index % 100}`, targetTypeKey: `T${(index + 1) % 100}`, multiplicity: 'MULTI' })) }
  const graph = buildOntologyGraph(data)
  expect(graph.nodes).toHaveLength(100)
  expect(graph.edges).toHaveLength(500)
  expect(graph.invalidRelations).toEqual([])
})
