<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import cytoscape, { type Core, type StylesheetJson } from 'cytoscape'
import coseBilkent from 'cytoscape-cose-bilkent'
import { hierarchyPositions } from '../ontologyGraphView'
import type { OntologyProjectionNode, OntologyProjectionEdge } from '../ontologyProjection'

cytoscape.use(coseBilkent)
const props = defineProps<{ nodes: OntologyProjectionNode[]; edges: OntologyProjectionEdge[]; selectedNodeId: string; selectedEdgeId: string; highlightedIds: string[] }>()
const emit = defineEmits<{ 'select-node': [id: string]; 'select-edge': [id: string] }>()
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const host = ref<HTMLDivElement>(); const layoutMode = ref('hierarchy'); const zoom = ref(1); const unavailable = ref(false)
let cy: Core | undefined
let resizeObserver: ResizeObserver | undefined
let themeObserver: MutationObserver | undefined
let activeLayout: ReturnType<Core['layout']> | undefined
let fittedWidth = 0
let compactAtFit = window.innerWidth <= 650
const positions = new Map<string, { x: number; y: number }>()
function styles(): StylesheetJson {
  const css = getComputedStyle(host.value!)
  const color = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback
  const primary = color('--el-color-primary', '#2563eb')
  return [
    { selector: 'node', style: { label: 'data(label)', width: 164, height: 44, shape: 'round-rectangle', 'background-color': color('--mc-bg-elevated', '#ffffff'), 'border-color': color('--mc-border', '#cbd5e1'), 'border-width': 1.5, color: color('--mc-text-primary', '#1e293b'), 'font-size': 12, 'font-family': css.fontFamily, 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'ellipsis', 'text-max-width': '145px' } },
    { selector: 'node[kind = "objectProperty"], node[kind = "dataProperty"]', style: { shape: 'round-diamond', width: 144, height: 54 } },
    { selector: 'node[kind = "individual"]', style: { shape: 'ellipse' } },
    { selector: 'edge', style: { label: 'data(label)', 'curve-style': 'bezier', width: 1.5, 'line-color': color('--mc-text-secondary', '#64748b'), 'target-arrow-color': color('--mc-text-secondary', '#64748b'), 'target-arrow-shape': 'triangle', 'font-size': 10, color: color('--mc-text-secondary', '#64748b'), 'text-background-color': color('--mc-bg-elevated', '#ffffff'), 'text-background-opacity': 1, 'text-background-padding': '3px', 'text-rotation': 'autorotate' } },
    { selector: 'edge[kind = "someValuesFrom"], edge[kind = "allValuesFrom"]', style: { 'line-style': 'dashed' } },
    { selector: 'edge[kind = "equivalentClasses"], edge[kind = "disjointClasses"], edge[kind = "inverseOf"]', style: { 'target-arrow-shape': 'none', 'source-arrow-shape': 'none' } },
    { selector: '.matched', style: { 'border-color': primary, 'border-width': 3 } },
    { selector: 'node.chosen', style: { 'background-color': color('--el-color-primary-light-9', '#eff6ff'), 'border-color': primary, 'border-width': 3 } },
    { selector: 'edge.chosen', style: { 'line-color': primary, 'target-arrow-color': primary, width: 3, color: primary } },
  ]
}
function decorate() {
  if (!cy) return
  cy.elements().removeClass('chosen matched')
  for (const id of props.highlightedIds) cy.getElementById(id).addClass('matched')
  cy.getElementById(props.selectedNodeId || props.selectedEdgeId).addClass('chosen')
}
function fit() {
  cy?.fit(undefined, 24)
  const width = host.value?.getBoundingClientRect().width || 0
  if (width > 0) { fittedWidth = width; compactAtFit = window.innerWidth <= 650 }
}
function resizeCanvas(entries: ResizeObserverEntry[]) {
  const size = entries.find(entry => entry.target === host.value)?.contentRect
  if (!cy || !size || size.width <= 0 || size.height <= 0) return
  cy.resize()
  const compact = window.innerWidth <= 650
  const majorWidthChange = Math.abs(size.width - fittedWidth) >= Math.max(80, fittedWidth * 0.25)
  if (!fittedWidth || compact !== compactAtFit || majorWidthChange) {
    fit()
    fittedWidth = size.width
    compactAtFit = compact
  }
}
function focus(id?: string) {
  if (!cy) return
  const target = cy.getElementById(id || props.selectedNodeId || props.selectedEdgeId || props.highlightedIds[0] || '')
  if (target.length) { cy.zoom(Math.max(cy.zoom(), 0.8)); cy.center(target) }
}
function scale(factor: number) { if (cy) cy.zoom({ level: Math.max(cy.minZoom(), Math.min(cy.maxZoom(), cy.zoom() * factor)), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } }) }
function arrange() {
  if (!cy || !props.nodes.length) return
  activeLayout?.stop()
  if (layoutMode.value === 'hierarchy') {
    const calculated = hierarchyPositions(props.nodes, props.edges)
    cy.nodes().positions(n => calculated.get(n.id())!)
    fit()
  } else {
    activeLayout = cy.layout({ name: 'cose-bilkent', animate: false, randomize: false, fit: true, padding: 30 } as cytoscape.LayoutOptions)
    activeLayout.run()
  }
  cy.nodes().forEach(n => { positions.set(n.id(), { ...n.position() }) })
}
function update() {
  if (!cy) return
  activeLayout?.stop()
  cy.nodes().forEach(n => { positions.set(n.id(), { ...n.position() }) })
  const first = positions.size === 0
  const calculated = hierarchyPositions(props.nodes, props.edges)
  cy.batch(() => {
    cy!.elements().remove()
    cy!.add(props.nodes.map(n => ({ group: 'nodes' as const, data: { id: n.id, label: n.label, kind: n.kind }, position: positions.get(n.id) ?? calculated.get(n.id) })))
    cy!.add(props.edges.map(e => ({ group: 'edges' as const, data: { id: e.id, source: e.source, target: e.target, label: e.label, kind: e.kind } })))
  })
  decorate()
  if (first) fit()
}
function keydown(event: KeyboardEvent) {
  if (!cy || event.target !== host.value) return
  const direction: Record<string, { x: number; y: number }> = { ArrowLeft: { x: 40, y: 0 }, ArrowRight: { x: -40, y: 0 }, ArrowUp: { x: 0, y: 40 }, ArrowDown: { x: 0, y: -40 } }
  if (direction[event.key]) { event.preventDefault(); cy.panBy(direction[event.key]!) }
  else if (event.key === '+' || event.key === '=') { event.preventDefault(); scale(1.25) }
  else if (event.key === '-') { event.preventDefault(); scale(0.8) }
}
onMounted(() => {
  try {
    cy = cytoscape({ container: host.value, elements: [], style: styles(), minZoom: 0.1, maxZoom: 3, boxSelectionEnabled: false, autounselectify: true })
    cy.on('tap', 'node', e => emit('select-node', e.target.id()))
    cy.on('tap', 'edge', e => emit('select-edge', e.target.id()))
    cy.on('zoom', () => { zoom.value = cy!.zoom() })
    update()
    resizeObserver = new ResizeObserver(resizeCanvas)
    resizeObserver.observe(host.value!)
    themeObserver = new MutationObserver(() => cy?.style(styles()))
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] })
  } catch {
    unavailable.value = true
    cy?.destroy(); cy = undefined
  }
})
watch(() => [props.nodes, props.edges], update)
watch(() => [props.selectedNodeId, props.selectedEdgeId, props.highlightedIds], decorate)
onBeforeUnmount(() => { activeLayout?.stop(); resizeObserver?.disconnect(); themeObserver?.disconnect(); cy?.destroy(); cy = undefined; positions.clear() })
defineExpose({ focus, fit })
</script>
<template>
  <div class="ontology-graph-canvas">
    <div class="graph-toolbar">
      <div class="graph-primary-controls">
        <div class="graph-view-actions">
          <el-button size="small" :disabled="unavailable" @click="fit">{{ tr('适应画布', 'Fit') }}</el-button>
          <el-button size="small" :disabled="unavailable || !(selectedNodeId || selectedEdgeId || highlightedIds.length)" @click="focus()">{{ tr('定位所选', 'Locate') }}</el-button>
        </div>
        <div class="graph-zoom-controls" role="group" :aria-label="tr('画布缩放', 'Canvas zoom')">
          <el-button size="small" :aria-label="tr('缩小', 'Zoom out')" :disabled="unavailable" @click="scale(0.8)">−</el-button>
          <output class="graph-zoom" :aria-label="tr('缩放比例', 'Zoom level')">{{ Math.round(zoom * 100) }}%</output>
          <el-button size="small" :aria-label="tr('放大', 'Zoom in')" :disabled="unavailable" @click="scale(1.25)">＋</el-button>
        </div>
      </div>
      <details class="graph-layout-controls">
        <summary>{{ tr('布局设置', 'Layout settings') }}</summary>
        <div class="graph-layout-options">
          <label>{{ tr('布局', 'Layout') }} <select v-model="layoutMode" :aria-label="tr('图形布局', 'Graph layout')" :disabled="unavailable" @change="arrange"><option value="hierarchy">{{ tr('层级', 'Hierarchy') }}</option><option value="relations">{{ tr('关系探索', 'Relationships') }}</option></select></label>
          <el-button size="small" :disabled="unavailable" @click="arrange">{{ tr('重新布局', 'Arrange') }}</el-button>
        </div>
      </details>
    </div>
    <p v-if="unavailable" role="status" class="graph-message">{{ tr('图形画布暂不可用，请使用定义目录与关系列表查看。', 'Canvas unavailable. Use the definition directory and relationship list.') }}</p>
    <div ref="host" class="graph-surface" tabindex="0" role="group" :aria-label="tr('本体画布，可拖动节点、平移和缩放；键盘选择请使用定义目录与关系列表', 'Ontology canvas: drag nodes, pan and zoom. Use the directory and link list for keyboard selection.')" @keydown="keydown" />
    <p class="graph-help">{{ tr('拖动节点调整位置 · 拖动空白处平移 · 滚轮缩放；布局不会修改本体。', 'Drag nodes to position, drag background to pan, scroll to zoom. Layout does not edit the ontology.') }}</p>
  </div>
</template>
<style scoped>
.ontology-graph-canvas{min-width:0}.graph-toolbar{display:flex;gap:8px;align-items:center;justify-content:space-between;padding:10px 12px;border-bottom:1px solid var(--mc-border);flex-wrap:wrap}.graph-toolbar label{font-size:12px;display:flex;gap:6px;align-items:center}.graph-toolbar select{background:var(--mc-bg-elevated);color:var(--mc-text-primary);border:1px solid var(--mc-border);border-radius:4px;padding:4px}.graph-actions{display:flex;gap:6px;align-items:center;flex-wrap:wrap}.graph-actions .el-button+.el-button{margin-left:0}.graph-zoom{min-width:36px;text-align:center;font-size:12px}.graph-surface{height:480px;position:relative;min-width:0;touch-action:none}.graph-surface:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}.graph-help,.graph-message{font-size:12px;color:var(--mc-text-secondary);line-height:1.6;margin:0;padding:8px 12px}.graph-help{border-top:1px solid var(--mc-border)}@media(max-width:650px){.graph-surface{height:340px}}
.graph-toolbar{display:block}.graph-primary-controls{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}.graph-view-actions,.graph-zoom-controls{display:flex;align-items:center;gap:6px;flex-wrap:nowrap}.graph-view-actions .el-button+.el-button,.graph-zoom-controls .el-button+.el-button{margin-left:0}.graph-zoom-controls{white-space:nowrap;flex-shrink:0;margin-left:auto}.graph-layout-controls{margin-top:8px;font-size:12px}.graph-layout-controls>summary{cursor:pointer;color:var(--mc-text-secondary)}.graph-layout-options{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding-top:8px}@media(max-width:650px){.graph-help{display:none}.graph-primary-controls{gap:8px}.graph-view-actions .el-button{padding:5px 8px}.graph-zoom-controls .el-button{padding:5px 8px}.graph-zoom{min-width:40px}}
</style>
