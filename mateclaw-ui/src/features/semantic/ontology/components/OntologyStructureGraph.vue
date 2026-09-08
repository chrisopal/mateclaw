<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Definition } from '../../api/types'
import { buildOntologyGraph } from '../ontologyGraphModel'
const props = defineProps<{ definition: Definition }>()
const { t } = useI18n()
const graph = computed(() => buildOntologyGraph(props.definition))
const selectedId = ref('')
const selected = computed(() => graph.value.nodes.find(node => node.id === selectedId.value))
const markerId = `ontology-arrow-${useId().replace(/:/g, '')}`
const zoom = ref(1), panX = ref(0), panY = ref(0)
const dragging = ref(false)
const viewport = ref<HTMLDivElement>()
let observer: ResizeObserver | undefined
let fitted = false
let pointer: { x: number; y: number } | null = null
function reset() {
  const width = viewport.value?.clientWidth ?? 0
  zoom.value = width > 0 ? Math.min(1, width / graph.value.width, (viewport.value?.clientHeight ?? 510) / graph.value.height) : 1
  panX.value = 0; panY.value = 0; fitted = width > 0
}
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => { if (!fitted) reset() })
    if (viewport.value) observer.observe(viewport.value)
  }
  reset()
})
onBeforeUnmount(() => observer?.disconnect())
watch(() => props.definition, () => { selectedId.value = ''; fitted = false; void nextTick(() => { if (viewport.value) observer?.observe(viewport.value); reset() }) })
function move(event: PointerEvent) {
  if (!pointer) return
  panX.value += event.clientX - pointer.x; panY.value += event.clientY - pointer.y
  pointer = { x: event.clientX, y: event.clientY }
}
function start(event: PointerEvent) {
  if (event.button !== 0 || (event.target as Element).closest('[data-type-node]')) return
  pointer = { x: event.clientX, y: event.clientY }; dragging.value = true
  ;(event.currentTarget as Element).setPointerCapture(event.pointerId)
}
function end() { pointer = null; dragging.value = false }
function scale(value: number) { zoom.value = Math.max(0.05, Math.min(3, value)) }
const related = computed(() => props.definition.relations.filter(relation => selected.value && (relation.sourceTypeKey === selected.value.type.key || relation.targetTypeKey === selected.value.type.key)))
const typeName = (key: string) => props.definition.types.find(type => type.key === key)?.label || key
const short = (value: string, length = 25) => value.length > length ? `${value.slice(0, length)}…` : value
</script>
<template>
  <section class="ontology-structure" :aria-label="t('semantic.structure.title')">
    <h3>{{ t('semantic.structure.title') }}</h3>
    <p v-if="!graph.nodes.length">{{ t('semantic.structure.empty') }}</p>
    <template v-else>
      <div class="structure-toolbar">
        <label>{{ t('semantic.structure.selectType') }}
          <select v-model="selectedId" data-structure-selector>
            <option value="">{{ t('semantic.structure.selectPrompt') }}</option>
            <option v-for="node in graph.nodes" :key="node.id" :value="node.id">{{ node.type.label }} ({{ node.type.key }})</option>
          </select>
        </label>
        <div class="zoom-actions">
          <button type="button" :aria-label="t('semantic.structure.zoomOut')" @click="scale(zoom / 1.25)">−</button>
          <span>{{ Math.round(zoom * 100) }}%</span>
          <button type="button" :aria-label="t('semantic.structure.zoomIn')" @click="scale(zoom * 1.25)">+</button>
          <button type="button" @click="reset">{{ t('semantic.structure.reset') }}</button>
        </div>
      </div>
      <div class="structure-layout" :class="{ 'has-selection': selected }">
        <div ref="viewport" class="structure-viewport" :class="{ dragging }" @pointerdown="start" @pointermove="move" @pointerup="end" @pointercancel="end" @lostpointercapture="end">
          <svg :width="graph.width" :height="graph.height" :style="{ transform: `translate(${panX}px, ${panY}px) scale(${zoom})` }" :aria-label="t('semantic.structure.title')" role="img">
            <defs><marker :id="markerId" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" class="arrow" /></marker></defs>
            <g v-for="edge in graph.edges" :key="edge.id" class="structure-edge">
              <title>{{ typeName(edge.relation.sourceTypeKey) }} → {{ edge.relation.label }} → {{ typeName(edge.relation.targetTypeKey) }}</title>
              <path :d="edge.path" :marker-end="`url(#${markerId})`" />
              <text :x="edge.labelX" :y="edge.labelY - 5" text-anchor="middle">{{ short(edge.relation.label) }}</text>
            </g>
            <g v-for="node in graph.nodes" :key="node.id" :transform="`translate(${node.x}, ${node.y})`" class="structure-node" :class="{ selected: selectedId === node.id }" data-type-node @click="selectedId = node.id">
              <title>{{ node.type.label }} ({{ node.type.key }})</title>
              <rect x="-120" y="-60" width="240" height="120" rx="6" />
              <text x="-106" y="-36" class="node-title">{{ short(node.type.label, 22) }}</text>
              <text x="-106" y="-17" class="node-key">{{ short(node.type.key, 30) }}</text>
              <text v-for="(property, index) in node.properties.slice(0, 3)" :key="index" x="-106" :y="3 + index * 17" class="node-property">{{ short(`${property.label}: ${t('semantic.values.' + property.valueType)}`, 30) }}</text>
              <text v-if="node.properties.length > 3" x="107" y="49" text-anchor="end" class="node-key">+{{ node.properties.length - 3 }}</text>
            </g>
          </svg>
        </div>
        <aside v-if="selected" class="structure-details" aria-live="polite">
          <template v-if="selected">
            <h3>{{ selected.type.label }}</h3><code>{{ selected.type.key }}</code>
            <p v-if="selected.type.description">{{ selected.type.description }}</p>
            <p v-if="selected.type.aliases?.length">{{ t('semantic.aliases') }}: {{ selected.type.aliases.join(', ') }}</p>
            <p v-if="selected.type.deprecated">{{ t('semantic.deprecated') }}</p>
            <h4>{{ t('semantic.properties') }} ({{ selected.properties.length }})</h4>
            <p v-if="!selected.properties.length">{{ t('semantic.structure.noProperties') }}</p>
            <article v-for="(property, index) in selected.properties" :key="index" class="detail-item">
              <strong>{{ property.label }}</strong> <code>{{ property.key }}</code>
              <p>{{ t('semantic.values.' + property.valueType) }} · {{ t('semantic.values.' + property.multiplicity) }}</p>
              <p v-if="property.fixedUnit">{{ t('semantic.fixedUnit') }}: {{ property.fixedUnit }}</p>
              <p v-if="property.description">{{ property.description }}</p>
              <p v-if="property.aliases?.length">{{ t('semantic.aliases') }}: {{ property.aliases.join(', ') }}</p>
              <p v-if="property.deprecated">{{ t('semantic.deprecated') }}</p>
              <p v-if="property.constraints?.allowedValues?.length">{{ t('semantic.allowedValues') }}: {{ property.constraints.allowedValues.join(', ') }}</p>
              <p v-if="property.constraints?.minimum != null">{{ t('semantic.minimum') }}: {{ property.constraints.minimum }}</p>
              <p v-if="property.constraints?.maximum != null">{{ t('semantic.maximum') }}: {{ property.constraints.maximum }}</p>
            </article>
            <h4>{{ t('semantic.relations') }} ({{ related.length }})</h4>
            <p v-if="!related.length">{{ t('semantic.structure.noRelations') }}</p>
            <article v-for="(relation, index) in related" :key="index" class="detail-item">
              <strong>{{ relation.label }}</strong> <code>{{ relation.key }}</code>
              <p>{{ typeName(relation.sourceTypeKey) }} → {{ typeName(relation.targetTypeKey) }}</p>
              <p>{{ t('semantic.values.' + relation.multiplicity) }}</p>
              <p v-if="relation.description">{{ relation.description }}</p>
              <p v-if="relation.aliases?.length">{{ t('semantic.aliases') }}: {{ relation.aliases.join(', ') }}</p>
              <p v-if="relation.deprecated">{{ t('semantic.deprecated') }}</p>
            </article>
          </template>
        </aside>
      </div>
    </template>
    <details v-if="graph.invalidRelations.length || graph.orphanProperties.length" class="structure-diagnostics">
      <summary>{{ t('semantic.structure.invalidReferences', { count: graph.invalidRelations.length + graph.orphanProperties.length }) }}</summary>
      <ul><li v-for="(relation, index) in graph.invalidRelations" :key="`r${index}`">{{ relation.label }} ({{ relation.key }}): {{ relation.sourceTypeKey }} → {{ relation.targetTypeKey }}</li>
        <li v-for="(property, index) in graph.orphanProperties" :key="`p${index}`">{{ property.label }} ({{ property.key }}): {{ property.ownerTypeKey }}</li></ul>
    </details>
  </section>
</template>
<style scoped>
.ontology-structure { min-width: 0; color: var(--mc-text-primary); }
.structure-toolbar, .zoom-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.structure-toolbar { justify-content: space-between; margin-bottom: 12px; }
.structure-toolbar label { display: flex; gap: 8px; align-items: center; min-width: 0; max-width: 100%; }
select, button { color: var(--mc-text-primary); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 4px; padding: 7px 10px; min-height: 34px; }
select { min-width: 0; max-width: 280px; } button { cursor: pointer; } button:focus-visible, select:focus-visible { outline: 2px solid var(--mc-primary, #1677ff); outline-offset: 2px; }
.structure-layout { display: grid; grid-template-columns: minmax(0, 1fr); border: 1px solid var(--mc-border); border-radius: 6px; overflow: hidden; }
.structure-layout.has-selection { grid-template-columns: minmax(0, 1fr) 300px; }
.structure-viewport { height: 510px; overflow: hidden; cursor: grab; touch-action: none; background: var(--mc-bg); }
.structure-viewport.dragging { cursor: grabbing; } svg { overflow: visible; transform-origin: 0 0; display: block; user-select: none; }
.structure-edge > path { fill: none; stroke: var(--mc-text-secondary); stroke-width: 1.5; } .arrow { fill: var(--mc-text-secondary); }
.structure-edge text { fill: var(--mc-text-primary); font-size: 11px; paint-order: stroke; stroke: var(--mc-bg); stroke-width: 4px; stroke-linejoin: round; }
.structure-node { cursor: pointer; } .structure-node rect { fill: var(--mc-bg-elevated); stroke: var(--mc-border); stroke-width: 1.5; }
.structure-node.selected rect { stroke: var(--mc-primary, #1677ff); stroke-width: 3; }
.structure-node text { fill: var(--mc-text-primary); font-size: 12px; pointer-events: none; }.structure-node .node-title { font-weight: 600; font-size: 14px; }.structure-node .node-key { fill: var(--mc-text-secondary); font-size: 10px; }
.structure-details { padding: 16px; border-left: 1px solid var(--mc-border); max-height: 510px; overflow: auto; overflow-wrap: anywhere; font-size: 13px; }
h3 { margin: 0 0 8px; } h4 { margin: 20px 0 8px; } .detail-item { border-top: 1px solid var(--mc-border); padding: 10px 0; }.detail-item p { margin: 5px 0; } code { color: var(--mc-text-secondary); font-size: 11px; }.structure-diagnostics { margin-top: 12px; overflow-wrap: anywhere; } summary { cursor: pointer; }
@media (max-width: 900px) { .structure-layout, .structure-layout.has-selection { grid-template-columns: minmax(0, 1fr); }.structure-viewport { height: 380px; }.structure-details { max-height: 360px; border-left: 0; border-top: 1px solid var(--mc-border); } }
@media (max-width: 480px) { .structure-toolbar label { width: 100%; } select { flex: 1; width: 0; }.structure-viewport { height: 310px; } }
</style>
