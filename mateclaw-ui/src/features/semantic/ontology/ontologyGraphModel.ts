import type { Definition } from '../api/types'

/** Stable grid keeps isolated types visible and avoids force-layout overlap. */
export function buildOntologyGraph(definition: Definition) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(definition.types.length)))
  const nodes = definition.types.map((type, index) => ({
    id: `type-${index}`, type, x: 160 + (index % columns) * 360,
    y: 220 + Math.floor(index / columns) * 290,
    properties: definition.properties.filter(property => property.ownerTypeKey === type.key),
  }))
  const byKey = new Map(nodes.map(node => [node.type.key, node]))
  const pairs = new Map<string, number>()
  const invalidRelations = definition.relations.filter(relation => !byKey.has(relation.sourceTypeKey) || !byKey.has(relation.targetTypeKey))
  const edges = definition.relations.flatMap((relation, index) => {
    const source = byKey.get(relation.sourceTypeKey), target = byKey.get(relation.targetTypeKey)
    if (!source || !target) return []
    const pair = [source.id, target.id].sort().join(':')
    const lane = pairs.get(pair) ?? 0
    pairs.set(pair, lane + 1)
    if (source === target) {
      const lift = 110 + lane * 40
      return [{ id: `relation-${index}`, relation, source, target,
        path: `M ${source.x - 60} ${source.y - 60} C ${source.x - 140} ${source.y - lift - 90}, ${source.x + 140} ${source.y - lift - 90}, ${source.x + 60} ${source.y - 60}`,
        labelX: source.x, labelY: source.y - lift - 45 }]
    }
    const dx = target.x - source.x, dy = target.y - source.y
    const length = Math.hypot(dx, dy)
    const offset = 35 + lane * 42
    // Canonical normal separates opposite-direction relations too.
    const direction = source.id < target.id ? 1 : -1
    const cx = (source.x + target.x) / 2 - dy / length * offset * direction
    const cy = (source.y + target.y) / 2 + dx / length * offset * direction
    const boundary = (node: typeof source, towardX: number, towardY: number) => {
      const vx = towardX - node.x, vy = towardY - node.y
      const scale = Math.min(120 / Math.max(Math.abs(vx), 0.001), 60 / Math.max(Math.abs(vy), 0.001))
      return [node.x + vx * scale, node.y + vy * scale]
    }
    const [sx, sy] = boundary(source, cx, cy), [tx, ty] = boundary(target, cx, cy)
    return [{ id: `relation-${index}`, relation, source, target,
      path: `M ${sx} ${sy} Q ${cx} ${cy} ${tx} ${ty}`,
      labelX: (sx! + 2 * cx + tx!) / 4, labelY: (sy! + 2 * cy + ty!) / 4 }]
  })
  return { nodes, edges, invalidRelations,
    orphanProperties: definition.properties.filter(property => !byKey.has(property.ownerTypeKey)),
    width: columns * 360, height: Math.max(370, 70 + Math.ceil(nodes.length / columns) * 290),
  }
}
