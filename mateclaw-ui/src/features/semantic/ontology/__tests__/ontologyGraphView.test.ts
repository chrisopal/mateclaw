import { describe, expect, it } from 'vitest'
import { graphView, hierarchyPositions } from '../ontologyGraphView'
import type { OntologyProjection } from '../ontologyProjection'
const model: OntologyProjection = {
  nodes: ['A', 'B', 'C', 'D'].map(id => ({ id, iri: `urn:${id}`, label: id, kind: 'class', axiomIds: [id] })),
  edges: [
    { id: 'ab', source: 'A', target: 'B', label: '子类属于', kind: 'subClassOf', axiomId: 'ab' },
    { id: 'bc', source: 'B', target: 'C', label: '至少一个', kind: 'someValuesFrom', axiomId: 'bc' },
  ], unprojectedAxiomIds: ['complex'],
}
describe('ontology graph browsing', () => {
  it('search highlights without hiding the connected schema unless explicitly requested', () => {
    const result = graphView(model, { query: 'B' })
    expect(result.matches).toEqual(['B'])
    expect(result.nodes).toHaveLength(4)
    expect(result.edges.map(e => e.id)).toEqual(['ab', 'bc'])
    const filtered = graphView(model, { query: 'B', onlyMatches: true })
    expect(filtered.nodes.map(n => n.id)).toEqual(['B'])
    expect(filtered.edges).toEqual([])
    expect(filtered.hiddenNodes).toBe(3)
  })
  it('takes one structural hop without fabricated edges or traversing the entire component', () => {
    const result = graphView(model, { neighborId: 'A' })
    expect(result.nodes.map(n => n.id)).toEqual(['A', 'B'])
    expect(result.edges.map(e => e.id)).toEqual(['ab'])
    expect(graphView(model, { edgeKind: 'someValuesFrom' }).edges.map(e => e.id)).toEqual(['bc'])
  })
  it('separates ontology individuals and applies node kind filters', () => {
    const mixed: OntologyProjection = { ...model, nodes: [...model.nodes, { id: 'individual:A', iri: 'urn:A', label: 'A', kind: 'individual', axiomIds: ['i'] }] }
    expect(graphView(mixed, { mode: 'individuals' }).nodes.map(n => n.id)).toEqual(['individual:A'])
    expect(graphView(mixed, { nodeKind: 'dataProperty' }).nodes).toEqual([])
    expect(graphView(mixed).nodes).toHaveLength(4)
  })
  it('bounds rendering, reports hidden nodes and prioritizes the selected search result', () => {
    const result = graphView(model, { query: 'D', limit: 2 })
    expect(result.nodes.map(n => n.id)).toContain('D')
    expect(result.truncated).toBe(true)
    expect(result.hiddenNodes).toBe(2)
    expect(result.edges.every(e => result.nodes.some(n => n.id === e.source) && result.nodes.some(n => n.id === e.target))).toBe(true)
  })
  it('computes independent positions without modifying the authoritative projection', () => {
    const original = JSON.stringify(model)
    const positions = hierarchyPositions(model.nodes, model.edges)
    expect(positions.size).toBe(4)
    expect(positions.get('B')!.y).toBeGreaterThan(positions.get('A')!.y)
    expect(JSON.stringify(model)).toBe(original)
  })
})
